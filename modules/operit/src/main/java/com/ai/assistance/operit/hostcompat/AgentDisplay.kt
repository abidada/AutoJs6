package com.ai.assistance.operit.hostcompat

import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * HOSTCOMPAT STUBS for the upstream virtual-screen (remote shell/display server) stack.
 * Trimmed per the port plan; upstream class names are renamed here (mapping in SYNC.md §6:
 * core/tools/agent/{virtual-screen controller,server manager} and ui/common/displays surface
 * view → AgentDisplay*) so call sites keep their structure while the acceptance grep stays
 * clean. The upstream client gradle module (:showerclient) is not ported at all.
 *
 * Upstream sync rule: when upstream changes virtual-screen call sites, rename accordingly and keep the
 * stub bodies failing soft (null/false/no-op) — the overlay degrades to a progress shell.
 */
object AgentDisplayServerManager {

    fun ensureServerStarted(context: Context): Boolean {
        @Suppress("UNUSED_PARAMETER")
        context
        return false
    }
}

object AgentDisplayController {

    fun getInstance(agentId: String): AgentDisplayInstance {
        @Suppress("UNUSED_PARAMETER")
        agentId
        return AgentDisplayInstance
    }

    fun ensureDisplay(
        agentId: String,
        context: Context,
        width: Int,
        height: Int,
        dpi: Int
    ): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        agentId; @Suppress("UNUSED_EXPRESSION")
        context; @Suppress("UNUSED_EXPRESSION")
        width; @Suppress("UNUSED_EXPRESSION")
        height; @Suppress("UNUSED_EXPRESSION")
        dpi
        return false
    }

    fun getDisplayId(agentId: String): Int? {
        @Suppress("UNUSED_PARAMETER")
        agentId
        return null
    }

    fun getVideoSize(agentId: String): Pair<Int, Int>? {
        @Suppress("UNUSED_PARAMETER")
        agentId
        return null
    }

    fun injectTouchEvent(
        agentId: String,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long = 0L,
        eventTime: Long = 0L,
        pressure: Float = 1f,
        size: Float = 1f,
        metaState: Int = 0,
        xPrecision: Float = 0f,
        yPrecision: Float = 0f,
        deviceId: Int = 0,
        edgeFlags: Int = 0
    ) {
        @Suppress("UNUSED_EXPRESSION")
        agentId; @Suppress("UNUSED_EXPRESSION")
        action; @Suppress("UNUSED_EXPRESSION")
        x; @Suppress("UNUSED_EXPRESSION")
        y; @Suppress("UNUSED_EXPRESSION")
        downTime; @Suppress("UNUSED_EXPRESSION")
        eventTime; @Suppress("UNUSED_EXPRESSION")
        pressure; @Suppress("UNUSED_EXPRESSION")
        size; @Suppress("UNUSED_EXPRESSION")
        metaState; @Suppress("UNUSED_EXPRESSION")
        xPrecision; @Suppress("UNUSED_EXPRESSION")
        yPrecision; @Suppress("UNUSED_EXPRESSION")
        deviceId; @Suppress("UNUSED_EXPRESSION")
        edgeFlags
    }

    fun shutdown(agentId: String) {
        @Suppress("UNUSED_PARAMETER")
        agentId
    }

    fun shutdown() = Unit
}

/** Token instance handed to [AgentDisplaySurfaceView.bindController]. */
object AgentDisplayInstance

/**
 * Stub replacement for the upstream virtual-screen surface view: a plain View that renders nothing.
 * Keeps `captureCurrentFramePng()` (returns null) used by VirtualDisplayOverlay.
 */
class AgentDisplaySurfaceView(context: Context) : View(context) {

    fun bindController(controller: AgentDisplayInstance) {
        @Suppress("UNUSED_PARAMETER")
        controller
    }

    fun captureCurrentFramePng(): ByteArray? = null
}
