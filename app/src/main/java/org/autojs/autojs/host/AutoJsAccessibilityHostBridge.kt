package org.autojs.autojs.host

import android.accessibilityservice.AccessibilityService as SystemAccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.ai.assistance.operit.hostcompat.HostAccessibilityBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.autojs.autojs.AutoJs
import org.autojs.autojs.core.accessibility.AccessibilityService
import org.autojs.autojs.core.automator.GlobalActionAutomator
import java.io.File
import java.io.FileOutputStream

/**
 * Operit 模块的无障碍后端（P4.1 / C16）。
 *
 * 归属模块：host
 *
 * 映射关系（方案 §9 P4.1）：
 *  - 节点树       → [AccessibilityService.instance].rootInActiveWindow（模块侧遍历并序列化）
 *  - tap/longPress/swipe → 独立 [GlobalActionAutomator]（主 looper handler，不依赖运行中脚本）
 *  - 全局动作     → 系统 AccessibilityService.performGlobalAction(id)
 *  - 截屏         → Android 11+ 无障碍 takeScreenshot（免 MediaProjection 二次授权，C17 收敛）
 *  - activity/包名 → 宿主 [AutoJs.instance].infoProvider 的 latestActivity / latestPackage
 *
 * 注入点：App.onCreate()，紧随 `OperitLibrary.init(this)` 之后。
 * 无障碍未连接时所有能力优雅降级（返回 null/false），与 bibi 桥先例一致。
 */
object AutoJsAccessibilityHostBridge : HostAccessibilityBackend {

    private const val SCREENSHOT_TIMEOUT_MILLIS = 5_000L

    private val appContext: Context
        get() = org.autojs.autojs.app.GlobalAppContext.get()

    private fun hostService(): AccessibilityService? = AccessibilityService.instance

    private fun systemService(): SystemAccessibilityService? =
        hostService() as? SystemAccessibilityService

    // ==================== 状态 ====================

    override fun isServiceRunning(): Boolean = hostService() != null

    // ==================== 节点 ====================

    override fun getRootNode(): AccessibilityNodeInfo? =
        runCatching { hostService()?.rootInActiveWindow }.getOrNull()

    // ==================== 手势 ====================

    /** 与 MCP AutomationTools.createGlobalActionAutomator 同构：主 looper 回调 + 独立实例。 */
    private fun automator(): GlobalActionAutomator =
        GlobalActionAutomator(appContext, Handler(Looper.getMainLooper())) {
            hostService() ?: throw IllegalStateException("accessibility service is not running")
        }

    override fun tap(x: Int, y: Int): Boolean =
        runCatching { automator().click(x, y) }.getOrDefault(false)

    override fun longPress(x: Int, y: Int): Boolean =
        runCatching { automator().longClick(x, y) }.getOrDefault(false)

    override fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Long,
    ): Boolean = runCatching {
        automator().swipe(startX, startY, endX, endY, durationMillis.coerceAtLeast(1L))
    }.getOrDefault(false)

    override fun performGlobalAction(actionId: Int): Boolean =
        runCatching { systemService()?.performGlobalAction(actionId) ?: false }.getOrDefault(false)

    // ==================== 截屏 ====================

    override fun takeScreenshotTo(path: String, format: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        val bitmap = captureViaAccessibility() ?: return false
        return try {
            saveBitmap(bitmap, path, format)
        } catch (_: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
    }

    /** Android R+ 无障碍截屏；无需 MediaProjection 授权弹窗（C17）。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureViaAccessibility(): Bitmap? {
        val service = systemService() ?: return null
        val deferred = CompletableDeferred<Bitmap?>()
        // takeScreenshot 返回错误码（ERROR_NONE=0），失败时回调 onFailure。
        service.takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            appContext.mainExecutor,
            object : SystemAccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: SystemAccessibilityService.ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    hardwareBuffer.close()
                    // 硬件位图无法直接压缩，转软件位图副本。
                    val software = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    bitmap?.recycle()
                    deferred.complete(software)
                }

                override fun onFailure(errorCode: Int) {
                    deferred.complete(null)
                }
            }
        )
        return runBlocking {
            withTimeoutOrNull(SCREENSHOT_TIMEOUT_MILLIS) { deferred.await() }
        }
    }

    private fun saveBitmap(bitmap: Bitmap, path: String, format: String): Boolean {
        val file = File(path)
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
        val compressFormat = when (format.lowercase()) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.PNG
        }
        return FileOutputStream(file).use { out ->
            bitmap.compress(compressFormat, 100, out)
            out.flush()
            true
        }
    }

    // ==================== 前台窗口信息 ====================

    override fun currentActivityName(): String? =
        runCatching {
            AutoJs.instance.infoProvider.latestActivity.takeIf { it.isNotBlank() }
        }.getOrNull()

    override fun currentPackageName(): String? =
        runCatching {
            AutoJs.instance.infoProvider.latestPackage.takeIf { it.isNotBlank() }
        }.getOrNull()
}
