/**
 * 语音唤醒前台服务（microphone 类型）：常驻低优先级通知 + AudioRecord 循环喂 OwwWakeEngine。
 *
 * - 命中唤醒词 → 拉起 FloatingAsrService(ACTION_WAKE_TRIGGERED)，由悬浮球交互层直接进入 LISTENING；
 * - 与识别互斥：AsrRecordingState.active 时释放自身 AudioRecord，识别结束后重开并重置引擎；
 * - 「仅充电时启用」按偏好在循环内门控；
 * - Android 14+ 不允许从 BOOT_COMPLETED 拉起麦克风前台服务，开机自启失败时静默忽略（需手动开启）。
 *
 * 归属模块：wake
 */
package com.brycewg.asrkb.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.host.AsrRecordingState
import com.brycewg.asrkb.store.Prefs
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 4103
        private const val HIT_DEBOUNCE_MS = 4_000L
        private const val RECORD_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

        const val ACTION_START = "com.brycewg.asrkb.action.WAKE_WORD_START"
        const val ACTION_STOP = "com.brycewg.asrkb.action.WAKE_WORD_STOP"

        fun start(context: Context) {
            try {
                val intent = Intent(context, WakeWordService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start wake word service", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, WakeWordService::class.java).apply { action = ACTION_STOP }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to dispatch wake word stop", t)
                try {
                    context.stopService(Intent(context, WakeWordService::class.java))
                } catch (t2: Throwable) {
                    Log.w(TAG, "Failed to stop wake word service directly", t2)
                }
            }
        }
    }

    private lateinit var prefs: Prefs
    private var engine: OwwWakeEngine? = null
    private var audioRecord: AudioRecord? = null
    private var loopThread: Thread? = null

    @Volatile
    private var running = false

    private var lastHitElapsedMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { LocaleHelper.wrap(it) })
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ensureChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enter foreground state", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                if (!prefs.wakeWordEnabled) {
                    running = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                startLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            loopThread?.interrupt()
        } catch (_: Throwable) {
        }
        loopThread = null
        releaseAudio()
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        super.onDestroy()
    }

    // ==================== 监听循环 ====================

    private fun startLoop() {
        if (running) return
        running = true
        val thread = Thread({ loop() }, "wake-word-loop")
        thread.priority = Thread.MIN_PRIORITY
        loopThread = thread
        thread.start()
    }

    private fun loop() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Missing RECORD_AUDIO permission; stopping wake service")
            running = false
            stopSelf()
            return
        }

        try {
            engine = createEngine()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create wake engine; stopping", t)
            running = false
            stopSelf()
            return
        }

        val chunk = ShortArray(OwwWakeEngine.CHUNK_SAMPLES)
        var record: AudioRecord? = null

        while (running && prefs.wakeWordEnabled) {
            try {
                if (prefs.wakeWordOnlyWhileCharging && !isCharging()) {
                    releaseAudio()
                    record = null
                    Thread.sleep(2_000)
                    continue
                }

                if (AsrRecordingState.active) {
                    // 识别会话进行中：让出麦克风，结束后重置引擎恢复监听
                    releaseAudio()
                    record = null
                    engine?.reset()
                    Thread.sleep(300)
                    continue
                }

                if (record == null) {
                    record = openAudioRecord()
                    if (record == null) {
                        Thread.sleep(1_000)
                        continue
                    }
                }

                val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n < chunk.size) continue

                val probability = engine?.process(chunk) ?: 0f
                if (probability >= prefs.wakeWordThresholdPercent / 100f &&
                    SystemClock.elapsedRealtime() - lastHitElapsedMs > HIT_DEBOUNCE_MS
                ) {
                    lastHitElapsedMs = SystemClock.elapsedRealtime()
                    Log.d(TAG, "Wake word hit (p=$probability)")
                    triggerWakeRecognition()
                }
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                Log.w(TAG, "Wake loop error", t)
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        releaseAudio()
        record = null
    }

    private fun triggerWakeRecognition() {
        try {
            val intent = Intent(this, com.brycewg.asrkb.ui.floating.FloatingAsrService::class.java)
                .setAction(com.brycewg.asrkb.ui.floating.FloatingAsrService.ACTION_WAKE_TRIGGERED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to dispatch wake trigger", t)
        }
    }

    // ==================== 音频 ====================

    private fun openAudioRecord(): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                OwwWakeEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf, OwwWakeEngine.CHUNK_SAMPLES * 8)
            val record = AudioRecord(
                RECORD_SOURCE,
                OwwWakeEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            record.startRecording()
            record
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to open AudioRecord for wake loop", t)
            null
        }
    }

    private fun releaseAudio() {
        val record = audioRecord ?: return
        audioRecord = null
        try {
            record.stop()
        } catch (_: Throwable) {
        }
        try {
            record.release()
        } catch (_: Throwable) {
        }
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager ?: return false
        val status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    // ==================== 引擎装配 ====================

    private fun createEngine(): OwwWakeEngine {
        fun load(assetPath: String): ByteBuffer =
            assets.openFd(assetPath).use { afd ->
                java.io.FileInputStream(afd.fileDescriptor).channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                ).order(ByteOrder.nativeOrder())
            }
        val wakeModel = try {
            load("openwakeword/userwake.tflite")
        } catch (_: Throwable) {
            load("openwakeword/hey_mycroft_v0.1.tflite")
        }
        return OwwWakeEngine(
            melspecModel = load("openwakeword/melspectrogram.tflite"),
            embeddingModel = load("openwakeword/embedding_model.tflite"),
            wakeModel = wakeModel
        )
    }

    // ==================== 通知 ====================

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_wake_word),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notif_channel_wake_word_desc)
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to create wake notification channel", t)
        }
    }

    private fun buildNotification(): Notification {
        val localized = LocaleHelper.wrap(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(localized.getString(R.string.notif_wake_word_title))
            .setContentText(localized.getString(R.string.notif_wake_word_desc))
            .setSmallIcon(R.drawable.microphone)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
