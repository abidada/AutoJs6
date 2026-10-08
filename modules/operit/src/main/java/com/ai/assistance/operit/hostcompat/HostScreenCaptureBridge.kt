package com.ai.assistance.operit.hostcompat

import android.content.Context
import android.media.projection.MediaProjection

/**
 * 截图桥（C17）：模块的 MediaProjection 需求复用宿主已授权的投屏会话
 * （AutoJs6 ScreenCapturer / ScreenCapturerForegroundService），避免第二次系统授权弹窗。
 *
 * P4.2 实装：宿主注入 [HostScreenCaptureBackend]（见 [OperitLibrary.screenCaptureBackend]），
 * 由宿主在**已授权且存活**的运行中脚本会话上共享引用；会话缺失/已失效时返回 null，
 * 模块侧截图工具优雅降级。
 *
 * 说明：宿主 `requestScreenCapture` 是脚本 runtime 级的一次性授权回调，不存在进程级全局
 * MediaProjection 实例；因此"共享"语义 = 借用已存在的会话而非新建授权流程。Android 11+
 * 的模块截图主路径走无障碍 takeScreenshot（P4.1，免授权），本桥仅覆盖 R 以下与显式
 * MediaProjection 需求。
 *
 * Upstream sync rule: StandardUITools.ensureMediaProjectionCaptureManager keeps calling
 * [acquireProjection]; upstream changes around its consent flow do not apply here.
 */
object HostScreenCaptureBridge {

    /**
     * Returns the host-owned, already-granted [MediaProjection], or null while unavailable.
     * The caller must pair every non-null result with [releaseProjection].
     */
    fun acquireProjection(context: Context): MediaProjection? {
        @Suppress("UNUSED_PARAMETER")
        context
        val backend = OperitLibrary.screenCaptureBackend ?: return null
        return runCatching { backend.acquireMediaProjection() }.getOrNull()
    }

    /** Releases one reference acquired via [acquireProjection]. */
    fun releaseProjection() {
        runCatching { OperitLibrary.screenCaptureBackend?.releaseMediaProjection() }
    }

    /** True when a reusable host session currently exists (no new consent dialog needed). */
    fun isSessionAvailable(): Boolean =
        runCatching { OperitLibrary.screenCaptureBackend?.hasActiveSession() == true }
            .getOrDefault(false)
}
