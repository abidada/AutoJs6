package com.ai.assistance.operit.hostcompat

import android.media.projection.MediaProjection

/**
 * Host screen-capture session surface for P4.2 (C17).
 *
 * The host owns a reused, already-granted [MediaProjection] (AutoJs6 script-runtime
 * `ScreenCapturer` session); the module borrows it instead of triggering a second system
 * consent dialog. Implemented host-side and injected through [OperitLibrary].
 *
 * Contract:
 *  - [acquireMediaProjection] returns null (never throws) when no live session exists.
 *  - Every non-null acquire must be paired with exactly one [releaseMediaProjection].
 *  - [hasActiveSession] is a cheap probe for UI/"unavailable" reporting.
 */
interface HostScreenCaptureBackend {

    fun acquireMediaProjection(): MediaProjection?

    fun releaseMediaProjection()

    fun hasActiveSession(): Boolean
}
