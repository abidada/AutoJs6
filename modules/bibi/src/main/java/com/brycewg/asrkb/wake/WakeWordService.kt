/**
 * 语音唤醒前台服务（microphone 类型）：常驻低优先级通知 + AudioRecord 循环喂 KwsWakeEngine。
 *
 * - 命中唤醒词 → 拉起 FloatingAsrService(ACTION_WAKE_TRIGGERED)，悬浮球直接进入「正在听」；
 * - 与识别互斥：AsrRecordingState.active 时释放自身 AudioRecord 并重建流，识别结束后恢复；
 * - 自愈：权限缺失/引擎失败/音频打开失败等一律不退出服务，循环内退避重试并向
 *   WakeServiceState 上报状态与心跳，供设置页如实显示与看门狗（WakeWatchdog）健康判定；
 * - 运行中再次收到 ACTION_START 触发原地重建（拆引擎与音频流后循环内自动重装）；
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

internal class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 4103
        private const val HIT_DEBOUNCE_MS = 2_500L
        private const val RECORD_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private const val CHUNK_SAMPLES = 1600 // 100ms @16kHz

        /** 自愈重试退避（引擎重建/权限轮询）；音频打开失败固定 1s 重试 */
        private val RETRY_DELAYS_MS = longArrayOf(10_000L, 30_000L, 60_000L)

        /** 连续读失败达到该次数即重建音频流（配合 50ms 退避约 1s） */
        private const val READ_ERROR_RECREATE_THRESHOLD = 20

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

        internal fun retryDelayMs(attempt: Int): Long =
            RETRY_DELAYS_MS[attempt.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
    }

    private lateinit var prefs: Prefs
    private var engine: KwsWakeEngine? = null
    private var audioRecord: AudioRecord? = null
    private var loopThread: Thread? = null

    @Volatile
    private var running = false

    /** 看门狗/重复 start 请求的原地重建标记：循环内拆掉引擎与音频流后自动重装 */
    @Volatile
    private var rebuildRequested = false

    private var lastHitElapsedMs = 0L

    /** 当前选中的唤醒词名;null = 全部预置词(不过滤)。sherpa KWS 的 createStream(keywords) 会把默认词表合并进来,故单选时在命中处过滤 */
    @Volatile
    private var activeKeywordName: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { LocaleHelper.wrap(it) })
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "service onCreate")
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
        Log.d(TAG, "onStartCommand action=" + (intent?.action ?: "null"))
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                if (!prefs.wakeWordEnabled) {
                    Log.d(TAG, "wake disabled by prefs; stopping")
                    running = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (running) {
                    // 服务已在运行（看门狗纠偏/唤醒词变更后的重复 start）：原地重建
                    Log.d(TAG, "already running; rebuild requested")
                    rebuildRequested = true
                } else {
                    startLoop()
                }
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
        try {
            loopThread?.join(1_000)
        } catch (_: Throwable) {
        }
        loopThread = null
        releaseAudio()
        closeEngine()
        WakeServiceState.reset()
        super.onDestroy()
    }

    // ==================== 监听循环 ====================

    private fun startLoop() {
        if (running) return
        Log.d(TAG, "wake loop starting")
        running = true
        WakeServiceState.update(WakeServiceState.Status.Starting)
        val thread = Thread({ loop() }, "wake-word-loop")
        thread.priority = Thread.MIN_PRIORITY
        loopThread = thread
        thread.start()
    }

    private fun loop() {
        val chunk = ShortArray(CHUNK_SAMPLES)
        var record: AudioRecord? = null
        var retryAttempt = 0
        var readErrors = 0
        var yielding = false

        while (running && prefs.wakeWordEnabled) {
            WakeServiceState.beat()
            try {
                if (rebuildRequested) {
                    rebuildRequested = false
                    releaseAudio()
                    record = null
                    closeEngine()
                    retryAttempt = 0
                    Log.d(TAG, "loop rebuilt")
                }

                // 让出麦克风的两类情况：识别录音进行中 / TTS 播报进行中。
                // 播报期间保持 KWS 麦克风打开会让扬声器声音同时进唤醒流——
                // 一则本机音频与录音并存会截断播报（见 startRecordingForUser 注释），
                // 二则含唤醒词的播报文案会触发自我唤醒形成打断循环。
                val ttsBusy = try {
                    com.brycewg.asrkb.tts.TtsPlaybackCoordinator.isBusy
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to read tts busy state", t)
                    false
                }
                if (AsrRecordingState.active || ttsBusy) {
                    if (!yielding) {
                        val reason = if (AsrRecordingState.active) "recording" else "tts-announcing"
                        Log.d(TAG, "wake mic paused (reason=$reason)")
                    }
                    yielding = true
                    // 识别会话进行中：让出麦克风，结束后重建流恢复监听
                    WakeServiceState.update(WakeServiceState.Status.Yielding)
                    releaseAudio()
                    record = null
                    engine?.recreateStream()
                    Thread.sleep(300)
                    continue
                }
                if (yielding) {
                    yielding = false
                    Log.d(TAG, "wake mic resumed")
                }

                if (record == null) {
                    // 自愈路径：权限缺失/引擎失败/音频打开失败均不退出服务，退避重试
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        WakeServiceState.update(
                            WakeServiceState.Status.Retrying,
                            WakeServiceState.FailReason.Permission
                        )
                        val delay = retryDelayMs(retryAttempt)
                        Log.w(TAG, "RECORD_AUDIO not granted; retry in ${delay}ms")
                        Thread.sleep(delay)
                        retryAttempt++
                        continue
                    }

                    if (engine == null) {
                        try {
                            engine = createEngine()
                        } catch (t: Throwable) {
                            WakeServiceState.update(
                                WakeServiceState.Status.Retrying,
                                WakeServiceState.FailReason.Engine
                            )
                            val delay = retryDelayMs(retryAttempt)
                            Log.w(TAG, "Failed to create wake engine; retry in ${delay}ms", t)
                            Thread.sleep(delay)
                            retryAttempt++
                            continue
                        }
                        Log.d(TAG, "wake engine created OK")
                    }

                    record = openAudioRecord()
                    if (record == null) {
                        WakeServiceState.update(
                            WakeServiceState.Status.Retrying,
                            WakeServiceState.FailReason.Audio
                        )
                        Log.w(TAG, "AudioRecord open failed; retry in 1s")
                        Thread.sleep(1_000)
                        continue
                    }
                    Log.d(TAG, "AudioRecord opened; listening")
                    WakeServiceState.update(WakeServiceState.Status.Listening)
                    retryAttempt = 0
                }

                val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    // 持续读失败（麦克风被系统异常抢占等）：退避后重建音频流，避免热循环与永久卡死
                    readErrors++
                    if (readErrors >= READ_ERROR_RECREATE_THRESHOLD) {
                        Log.w(TAG, "AudioRecord persistent read failure; recreating stream")
                        WakeServiceState.update(
                            WakeServiceState.Status.Retrying,
                            WakeServiceState.FailReason.Audio
                        )
                        releaseAudio()
                        record = null
                        readErrors = 0
                    } else {
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    continue
                }
                readErrors = 0

                val samples = FloatArray(n) { chunk[it] / 32768.0f }
                engine?.acceptWaveform(samples)
                val keyword = engine?.pollKeyword()
                if (keyword != null &&
                    SystemClock.elapsedRealtime() - lastHitElapsedMs > HIT_DEBOUNCE_MS
                ) {
                    val filter = activeKeywordName
                    if (filter != null && keyword != filter) {
                        Log.d(TAG, "Wake word hit ignored: $keyword (active=$filter)")
                    } else {
                        lastHitElapsedMs = SystemClock.elapsedRealtime()
                        Log.d(TAG, "Wake word hit: $keyword")
                        triggerWakeRecognition()
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                Log.w(TAG, "Wake loop error", t)
                WakeServiceState.update(
                    WakeServiceState.Status.Retrying,
                    WakeServiceState.FailReason.Unknown
                )
                releaseAudio()
                record = null
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        releaseAudio()
        WakeServiceState.update(WakeServiceState.Status.Idle)
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
                KwsWakeEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf, CHUNK_SAMPLES * 8)
            val record = AudioRecord(
                RECORD_SOURCE,
                KwsWakeEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            // startRecording 抛异常时字段尚未赋值，releaseAudio() 够不到，
            // 必须在这里释放，否则自愈循环会按秒持续泄漏 AudioRecord
            try {
                record.startRecording()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start AudioRecord for wake loop", t)
                record.release()
                return null
            }
            audioRecord = record
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

    // ==================== 引擎装配 ====================

    private fun closeEngine() {
        val e = engine ?: return
        engine = null
        try {
            e.close()
        } catch (_: Throwable) {
        }
    }

    private fun createEngine(): KwsWakeEngine {
        val selected = try {
            prefs.wakeWordSelected
        } catch (e: Throwable) {
            ""
        }
        val keywords = com.brycewg.asrkb.wake.WakeWordStore.buildActiveKeywords(this, selected)
        // keywords 为 null = 全部预置词(或选中名已失效),不过滤;非 null 才按选中名过滤
        activeKeywordName = if (keywords != null) selected.trim() else null
        return KwsWakeEngine(assets, keywords)
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
