/**
 * ASR result broadcaster for the BIBI (说点啥) library.
 *
 * 归属模块：悬浮球识别会话
 *
 * Fan-out point for streaming recognition results (floating-ball path and IME path).
 * The host (AutoJs6) registers a listener at `BibiLibrary.init()` to print final
 * results into the global console; scripts subscribe via the `$bibi` JS API.
 *
 * Callbacks originate from ASR engine threads — the broadcaster only forwards
 * them; consumers are responsible for their own thread switching.
 *
 * zh-CN: 识别结果(部分/最终/错误)的库内广播器, 线程安全, 只透传不做线程切换.
 */
package com.brycewg.asrkb.host

import java.util.concurrent.CopyOnWriteArrayList

object AsrResultBroadcaster {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(msg: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @JvmStatic
    fun add(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    @JvmStatic
    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    @JvmStatic
    fun firePartial(text: String) {
        if (listeners.isEmpty()) return
        for (l in listeners) {
            runCatching { l.onPartial(text) }
        }
    }

    @JvmStatic
    fun fireFinal(text: String) {
        if (listeners.isEmpty()) return
        for (l in listeners) {
            runCatching { l.onFinal(text) }
        }
    }

    @JvmStatic
    fun fireError(msg: String) {
        if (listeners.isEmpty()) return
        for (l in listeners) {
            runCatching { l.onError(msg) }
        }
    }
}
