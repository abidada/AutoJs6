package org.autojs.autojs.host

import android.media.projection.MediaProjection
import com.ai.assistance.operit.hostcompat.HostScreenCaptureBackend
import org.autojs.autojs.AutoJs
import java.util.concurrent.atomic.AtomicInteger

/**
 * Operit 模块的截图会话后端（P4.2 / C17）。
 *
 * 归属模块：host
 *
 * 设计要点（2026-10-09 截屏重构后）：宿主的截屏会话已经提升到**应用级单例**（[AutoJs.screenCaptureManager]），
 * 跨脚本共享同一份 MediaProjection —— 系统授权框每进程只弹一次（与 AutoX 行为一致）。
 * 因此本桥不再需要"遍历运行中脚本 → 反射读取其私有 mMediaProjection"，
 * 直接从应用级 manager 读取即可，代码路径显著简化且不再依赖反射。
 *
 * 引用计数：借出/归还在本对象内计数，仅用于 UI 可用性上报；宿主会话生命周期由
 * ScreenCaptureManager / ScreenCapturerForegroundService 掌控，模块不会主动 stop
 * 宿主会话（避免误伤其他脚本与共享会话）。
 *
 * 无活跃会话时全部返回 null/false —— 模块截图主路径（Android 11+）走无障碍 takeScreenshot，
 * 免授权，不依赖本桥。
 */
object AutoJsScreenCaptureHostBridge : HostScreenCaptureBackend {

    private val refCount = AtomicInteger(0)

    override fun acquireMediaProjection(): MediaProjection? {
        val projection = findLiveProjection() ?: return null
        refCount.incrementAndGet()
        return projection
    }

    override fun releaseMediaProjection() {
        refCount.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    override fun hasActiveSession(): Boolean = findLiveProjection() != null

    /** 当前借出引用数（诊断/上报用）。 */
    fun activeReferences(): Int = refCount.get()

    private fun findLiveProjection(): MediaProjection? {
        val manager = runCatching { AutoJs.instance.screenCaptureManager }.getOrNull() ?: return null
        val capturer = runCatching { manager.screenCapturer }.getOrNull() ?: return null
        if (!runCatching { capturer.isValid() }.getOrDefault(false)) return null
        return runCatching { capturer.mediaProjection }.getOrNull()
    }
}
