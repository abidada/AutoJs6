/**
 * 剪贴板端口工厂：IME 桥接移除后恒返回 Direct 端口（读系统剪贴板）。
 *
 * 归属模块：clipboard
 */
package com.brycewg.asrkb.clipboard

import android.content.Context
import com.brycewg.asrkb.store.Prefs

object SystemClipboardPortFactory {

    /** 仅保留系统剪贴板直读路径（手动同步场景）。 */
    fun create(
        context: Context,
        @Suppress("UNUSED_PARAMETER") prefs: Prefs,
        @Suppress("UNUSED_PARAMETER") activatedBridgeTargetPackage: String? = null
    ): SystemClipboardPort = DirectSystemClipboardPort(context)
}
