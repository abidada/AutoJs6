package org.autojs.autojs.host

import android.media.projection.MediaProjection
import com.ai.assistance.operit.hostcompat.HostScreenCaptureBackend
import org.autojs.autojs.AutoJs
import org.autojs.autojs.engine.JavaScriptEngine
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicInteger

/**
 * Operit 模块的截图会话后端（P4.2 / C17）。
 *
 * 归属模块：host
 *
 * 设计要点：宿主 `requestScreenCapture` 是**脚本 runtime 级**的一次性授权回调，没有进程级
 * 全局 MediaProjection 单例。因此"复用宿主授权"的可行语义是：在**已存在的活跃脚本会话**中
 * 借用其 `ScreenCapturer` 持有的 MediaProjection，而不是自行发起第二次系统授权。
 *
 * 实现路径：遍历 `AutoJs.instance.scriptEngineService.getEngines()` → `JavaScriptEngine.runtime`
 * → `runtime.images.screenCapturer` → 反射读取其私有 `mMediaProjection`（ScreenCapturer 未暴露
 * getter，字段名稳定；反射失败按不可用处理）。
 *
 * 引用计数：借出/归还在本对象内计数，仅用于 UI 可用性上报；宿主会话生命周期由脚本本体掌控，
 * 模块不会主动 stop 宿主会话（避免误伤运行中脚本）。
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
        val service = runCatching { AutoJs.instance.scriptEngineService }.getOrNull() ?: return null
        val engines = runCatching { service.engines }.getOrNull() ?: return null
        for (engine in engines) {
            if (engine !is JavaScriptEngine) continue
            val runtime = runCatching { engine.runtime }.getOrNull() ?: continue
            val capturer = runCatching { runtime.images.screenCapturer }.getOrNull() ?: continue
            val projection = readProjectionField(capturer) ?: continue
            return projection
        }
        return null
    }

    /**
     * ScreenCapturer 未暴露 mMediaProjection getter；反射读取该私有字段。
     * 字段名在 com.stardust / org.autojs 两版实现中均为 `mMediaProjection`。
     */
    private val projectionField: Field? by lazy {
        runCatching {
            val clazz = Class.forName("org.autojs.autojs.core.image.capture.ScreenCapturer")
            clazz.getDeclaredField("mMediaProjection").apply { isAccessible = true }
        }.getOrElse {
            runCatching {
                val clazz = Class.forName("com.stardust.autojs.core.image.capture.ScreenCapturer")
                clazz.getDeclaredField("mMediaProjection").apply { isAccessible = true }
            }.getOrNull()
        }
    }

    private fun readProjectionField(capturer: Any): MediaProjection? {
        val field = projectionField ?: return null
        return runCatching { field.get(capturer) as? MediaProjection }.getOrNull()
    }
}
