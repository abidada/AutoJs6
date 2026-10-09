package org.autojs.autojs.core.image.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xiaoyu.ai.R
import java.lang.ref.WeakReference

/**
 * Foreground service that owns the living MediaProjection across script runs.
 *
 * <p>
 * zh-CN: 跨脚本持有活跃 MediaProjection 的前台服务.
 *
 * <p>
 * Historically this service was a hollow shell whose only job was to satisfy the
 * Android 14 "mediaProjection FGS must be running" requirement. It is now upgraded
 * (modeled after AutoX's <code>CaptureForegroundService</code>) to:
 *
 * <ul>
 *   <li>Hold a [WeakReference] to the live [MediaProjection] so the service is the
 *       authoritative owner across script runs.</li>
 *   <li>Expose a notification with a "停止截图" action — the only user-facing way
 *       in the app to stop an in-progress capture (parity with AutoX).</li>
 *   <li>Listen to [MediaProjection.Callback.onStop] (system revoked, user stopped
 *       from system UI, etc.) and shut itself down, clearing the projection.</li>
 * </ul>
 *
 * <p>
 * The service intentionally does NOT stop when a script exits — the projection
 * is meant to be shared across scripts (see [ScreenCaptureManager]).
 *
 * zh-CN: 服务在脚本退出时不会停止 —— 截屏会话是被多脚本共享的 (见 [ScreenCaptureManager]).
 *
 * Created by SuperMonster003 on Apr 10, 2022.
 * Transformed by SuperMonster003 on Dec 6, 2023.
 * Refactored to own MediaProjection + notification stop-action, modeled after AutoX's
 * CaptureForegroundService.
 */
class ScreenCapturerForegroundService : Service() {

    private class ServiceCallback(service: ScreenCapturerForegroundService) : MediaProjection.Callback() {
        private val serviceRef = WeakReference(service)

        override fun onStop() {
            serviceRef.get()?.stopServiceInternal()
        }
    }

    private val callback = ServiceCallback(this)

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else 0,
        )
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServiceInternal()
            ACTION_REGISTER -> {
                getMediaProjection()?.unregisterCallback(callback)
                // Use a weak reference so the MediaProjection doesn't leak the Service.
                // zh-CN: 用弱引用避免 MediaProjection 反向持有 Service.
                getMediaProjection()?.registerCallback(callback, Handler(mainLooper))
            }
            ACTION_UNREGISTER -> {
                getMediaProjection()?.unregisterCallback(callback)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_capturer_foreground_notification_title))
            .setContentText(getString(R.string.screen_capturer_foreground_notification_text))
            .setSmallIcon(R.drawable.autojs6_status_bar_icon)
            .setWhen(System.currentTimeMillis())
            .setOngoing(false)
            .setVibrate(LongArray(0))
            .addAction(createStopAction())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_capturer_foreground_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.screen_capturer_foreground_notification_channel_name)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createStopAction(): NotificationCompat.Action {
        val pendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            Intent(this, ScreenCapturerForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            null,
            getString(R.string.screen_capturer_foreground_notification_action_stop),
            pendingIntent,
        ).build()
    }

    private fun removeNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    private fun stopServiceInternal() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Unregister the projection callback before stopping it, so the (empty) onStop
        // dispatch can never reach an already-torn-down handler.
        // zh-CN: 先反注册回调再 stop, 避免 onStop 派发到已销毁的 handler.
        getMediaProjection()?.unregisterCallback(callback)
        clearMediaProjection()
        removeNotification()
        instance = null
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 0xCF

        private const val REQUEST_CODE_STOP = 12

        private const val ACTION_STOP = "org.autojs.autojs.core.image.capture.STOP_SERVICE"
        private const val ACTION_REGISTER = "org.autojs.autojs.core.image.capture.REGISTER_CALLBACK"
        private const val ACTION_UNREGISTER = "org.autojs.autojs.core.image.capture.UNREGISTER_CALLBACK"

        private val CHANNEL_ID = ScreenCapturerForegroundService::class.java.name + ".foreground"

        @Volatile
        var instance: ScreenCapturerForegroundService? = null
            private set

        @Volatile
        private var mediaProjectionRef: WeakReference<MediaProjection>? = null

        fun getMediaProjection(): MediaProjection? = mediaProjectionRef?.get()

        private fun clearMediaProjection() {
            mediaProjectionRef?.get()?.let { projection ->
                runCatching { projection.stop() }
            }
            mediaProjectionRef = null
        }

        /**
         * Called by [ScreenCaptureManager] right after obtaining a new [MediaProjection]
         * so this service can register a callback on it and keep it alive.
         *
         * Uses startService (not startForegroundService): the service is already running
         * in foreground mode at this point (ScreenCaptureRequester started it before the
         * authorization callback fired), and startForegroundService would trip the
         * "startForegroundService from background" restrictions on Android 12+ ROMs.
         *
         * zh-CN: 在拿到新的 [MediaProjection] 后由 [ScreenCaptureManager] 调用,
         * 让本服务注册回调并维持其活跃.
         * 用 startService (而非 startForegroundService): 此刻服务已在前台模式运行
         * (ScreenCaptureRequester 在授权回调触发前已启动它), 用 startForegroundService
         * 会在 Android 12+ ROM 上触发"后台启动前台服务"限制.
         */
        fun setMediaProjection(context: Context, media: MediaProjection) {
            // Clear any stale reference before replacing it.
            // zh-CN: 替换前先清掉旧引用.
            clearMediaProjection()
            mediaProjectionRef = WeakReference(media)
            context.startService(
                Intent(context, ScreenCapturerForegroundService::class.java).apply {
                    action = ACTION_REGISTER
                },
            )
        }

        fun stopService() {
            instance?.stopServiceInternal()
        }
    }
}
