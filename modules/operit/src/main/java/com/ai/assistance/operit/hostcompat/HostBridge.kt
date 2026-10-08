package com.ai.assistance.operit.hostcompat

/**
 * Host capability surface consumed by the Operit module (P1.4 skeleton).
 *
 * The three concrete bridges live beside this file:
 *  - [LocalAccessibilityProvider] — accessibility backend → host AccessibilityService (C16, P4.1)
 *  - [HostScreenCaptureBridge]    — MediaProjection session reuse (C17, P4.2)
 *  - [BibiSpeechBridge]           — STT/TTS/wake delegation to bibi (C15, P4.3)
 *
 * Kept as the single documentation/entry point for host wiring (port plan §3 wiring point ③:
 * `OperitLibrary.init(host)` in `App.kt`).
 */
interface HostBridge
