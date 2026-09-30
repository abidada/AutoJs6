/**
 * BIBI (说点啥) library facade.
 *
 * 归属模块：宿主接线
 *
 * Initialization entry invoked by the host application (AutoJs6 `App.onCreate`).
 * Takes over the subset of the former `App.kt` responsibilities that are safe
 * inside a host application:
 * - debug/api log stores + crash logging (chains to the host's own handler)
 * - Shizuku bootstrap for privileged keep-alive
 * - floating ball / keep-alive service auto-start (only when overlay is granted)
 * - VAD & offline denoiser preloads
 * - legacy zipformer model cleanup
 *
 * Explicitly NOT taken over (owned by the host or dropped by design):
 * - dynamic color takeover, in-app locale override (would alter host UI behavior)
 * - anonymous analytics (removed per trimming decision)
 * - exclude-from-recents activity lifecycle takeover
 */
package com.brycewg.asrkb.host

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.brycewg.asrkb.asr.OfflineSpeechDenoiserManager
import com.brycewg.asrkb.asr.VadDetector
import com.brycewg.asrkb.store.ApiLogStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import com.brycewg.asrkb.ui.floating.FloatingKeepAliveService
import com.brycewg.asrkb.ui.floating.PrivilegedKeepAliveScheduler
import com.brycewg.asrkb.ui.floating.PrivilegedKeepAliveStarter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object BibiLibrary {

    private const val TAG = "BibiLibrary"

    /** Constant replacement for the former `BuildConfig.BUILD_TYPE` (not available in libraries). */
    private const val BUILD_TYPE = "library"

    @Volatile
    private var initialized = false

    /**
     * Pluggable host console printer. The host (AutoJs6) sets this to a function
     * that writes the line into its global console (e.g. `AutoJs.instance.console.println`).
     * When unset, lines fall back to android.util.Log.
     *
     * zh-CN: 宿主控制台打印函数, 由宿主在 init 前后设置; 未设置时退回 Logcat.
     */
    @Volatile
    var consolePrinter: ((String) -> Unit)? = null

    /**
     * Whether partial (streaming) results are printed to the host console.
     * Off by default to avoid flooding; constant-level switch to keep the
     * BiBi settings UI 1:1 with the original app.
     */
    @Volatile
    var printPartial: Boolean = false
        private set

    @JvmStatic
    fun setPrintPartial(enabled: Boolean) {
        printPartial = enabled
    }

    private val builtinResultListener = object : AsrResultBroadcaster.Listener {
        override fun onPartial(text: String) {
            if (!printPartial) return
            printToHostConsole("[BIBI] ...: $text")
        }

        override fun onFinal(text: String) {
            printToHostConsole("[BIBI] 识别: $text")
        }

        override fun onError(msg: String) {
            printToHostConsole("[BIBI] 错误: $msg")
        }
    }

    private fun printToHostConsole(line: String) {
        val printer = consolePrinter
        if (printer != null) {
            runCatching { printer(line) }
        } else {
            Log.i(TAG, line)
        }
    }

    @JvmStatic
    fun init(app: Application) = init(app, null)

    @JvmStatic
    fun init(app: Application, permissionRouter: BibiHostPermissionRouter?) {
        if (initialized) return
        initialized = true

        // BiBi pages delegate permission request actions to the host via this router.
        permissionRouter?.let { PermissionRouter.host = it }

        // Real-time result printing into the host console (P4.2).
        AsrResultBroadcaster.add(builtinResultListener)

        DebugLogManager.initialize(app)
        ApiLogStore.initialize(app)
        DebugLogManager.installUncaughtExceptionHandler(app)
        DebugLogManager.inspectHistoricalProcessExit(app)
        DebugLogManager.updateProcessStateSummary("phase=bibi_library_init")
        DebugLogManager.logBase(
            app,
            "app",
            "on_create",
            data = mapOf("buildType" to BUILD_TYPE)
        )

        PrivilegedKeepAliveStarter.initShizuku(app)

        // 若用户在设置中启用了悬浮球且已授予悬浮窗权限，则启动悬浮球服务
        try {
            val prefs = Prefs(app)
            PrivilegedKeepAliveScheduler.update(app)
            val canOverlay = Settings.canDrawOverlays(app)

            // 启动语音识别悬浮球
            if (prefs.floatingAsrEnabled && canOverlay) {
                val intent = Intent(app, FloatingAsrService::class.java).apply {
                    action = FloatingAsrService.ACTION_SHOW
                }
                app.startService(intent)
                DebugLogManager.logBase(app, "float", "app_start_show_service")
            }

            if (prefs.floatingKeepAliveEnabled) {
                FloatingKeepAliveService.start(app)
                DebugLogManager.logBase(app, "keepalive", "app_start_service")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start overlay services", t)
            DebugLogManager.logWarning(app, "app", "overlay_services_start_failed", t)
        }

        // 预加载 VAD：仅当已开启“静音自动停止”时，避免首次录音时的模型加载延迟
        try {
            val prefs = Prefs(app)
            if (prefs.autoStopOnSilenceEnabled) {
                VadDetector.preload(app, 16000, prefs.autoStopSilenceSensitivity)
                DebugLogManager.logBase(app, "asr", "vad_preload_started")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to preload VAD", t)
            DebugLogManager.logWarning(app, "asr", "vad_preload_failed", t)
        }

        // 预加载离线降噪模型：首次降噪会同步加载 JNI 与 ONNX 会话，
        // 若落在采集/编码协程内会挤占单帧预算并丢掉句首音频，因此提前在 IO 线程预热。
        try {
            val prefs = Prefs(app)
            if (prefs.offlineDenoiseEnabled) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val ready = OfflineSpeechDenoiserManager.preload(app, prefs)
                    DebugLogManager.log(
                        "asr",
                        "denoiser_preload_done",
                        data = mapOf("ready" to ready)
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to preload offline denoiser", t)
            DebugLogManager.logWarning(app, "asr", "denoiser_preload_failed", t)
        }

        // 清理已移除的 Zipformer 模型文件（仅执行一次）
        try {
            val prefs = Prefs(app)
            if (!prefs.zipformerCleanupDone) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val externalBase = app.getExternalFilesDir(null)
                    val bases = mutableListOf<File>()
                    if (externalBase != null) bases.add(externalBase)
                    if (externalBase == null || externalBase != app.filesDir) bases.add(app.filesDir)
                    bases.forEach { base ->
                        val zipformerDir = File(base, "zipformer")
                        if (zipformerDir.exists()) {
                            val deleted = zipformerDir.deleteRecursively()
                            if (!deleted) {
                                Log.w(TAG, "Failed to delete zipformer dir: ${zipformerDir.path}")
                                DebugLogManager.logWarning(
                                    app,
                                    "storage",
                                    "zipformer_cleanup_partial",
                                    data = mapOf("path" to zipformerDir.path.takeLast(48))
                                )
                            }
                        }
                    }
                    prefs.zipformerCleanupDone = true
                    DebugLogManager.logBase(app, "storage", "zipformer_cleanup_done")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Zipformer cleanup init failed", t)
            DebugLogManager.logWarning(app, "storage", "zipformer_cleanup_init_failed", t)
        }
    }
}
