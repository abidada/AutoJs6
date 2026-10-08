package com.ai.assistance.operit.hostcompat

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Host accessibility primitive surface for P4.1 (C16).
 *
 * Implemented on the host side (AutoJs6) and injected through [OperitLibrary] using the
 * bibi `ScriptHost.bridge` delegate precedent: the module declares the interface with
 * platform-SDK types only, the host wires the concrete backend at `App.onCreate()`.
 *
 * The host backend maps onto:
 *  - root node snapshot   → `AccessibilityService.instance.rootInActiveWindow`
 *  - tap/longPress/swipe  → `GlobalActionAutomator` (main-looper handler, standalone)
 *  - global actions       → `AccessibilityService.performGlobalAction(id)`
 *  - screenshot           → Android R+ accessibility `takeScreenshot` (no MediaProjection)
 *  - activity/package     → host accessibility event tracking (latestActivity/latestPackage)
 *
 * Upstream sync rule: this file is host-only surface (absent upstream); the AIDL file
 * `IAccessibilityProvider.aidl` keeps mapping 1:1 to upstream diffs.
 */
interface HostAccessibilityBackend {

    /** True when the host accessibility service is connected and operational. */
    fun isServiceRunning(): Boolean

    /**
     * Fresh snapshot of the active window root node, or null when unavailable.
     * The returned [AccessibilityNodeInfo] is system-pool managed; consumers must
     * traverse it promptly (no cross-call caching of the node itself).
     */
    fun getRootNode(): AccessibilityNodeInfo?

    /** Coordinate tap. Returns false when the gesture cannot be dispatched. */
    fun tap(x: Int, y: Int): Boolean

    /** Coordinate long press. Returns false when the gesture cannot be dispatched. */
    fun longPress(x: Int, y: Int): Boolean

    /** Linear swipe gesture over [durationMillis]. */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Long): Boolean

    /** Standard [android.accessibilityservice.AccessibilityService] global action id. */
    fun performGlobalAction(actionId: Int): Boolean

    /**
     * Captures the screen synchronously and saves it to [path] using [format]
     * ("png"/"jpg"/"jpeg", case-insensitive; anything else falls back to PNG).
     * Returns false on pre-R devices, missing service, or capture failure.
     */
    fun takeScreenshotTo(path: String, format: String): Boolean

    /** Foreground activity short name (e.g. `.MainActivity`), or null when unknown. */
    fun currentActivityName(): String?

    /** Foreground package name, or null when unknown. */
    fun currentPackageName(): String?
}
