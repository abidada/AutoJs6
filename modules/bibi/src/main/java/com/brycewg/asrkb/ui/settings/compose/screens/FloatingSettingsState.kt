/**
 * Compose 悬浮球设置页状态与系统能力 helper。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.HostA11yServiceResolver
import com.brycewg.asrkb.ui.floating.FloatingServiceManager

private const val FLOATING_SETTINGS_STATE_TAG = "FloatingSettingsState"

internal data class FloatingSettingsUiState(
    val asrEnabled: Boolean,
    val onlyWhenImeVisible: Boolean,
    val holdToRecordEnabled: Boolean,
    val directDragEnabled: Boolean,
    val alphaPercent: Float,
    val sizeDp: Int,
    val volumeKeyRecordingEnabled: Boolean,
    val volumeKeyRecordingMode: String,
    val volumeKeyStatusToastEnabled: Boolean,
    val shakeRecordingEnabled: Boolean,
    val shakeRecordingSensitivity: String,
    val shakeRecordingSoundEnabled: Boolean,
    val writePasteEnabled: Boolean,
    val wakeWordEnabled: Boolean
) {
    companion object {
        val placeholder: FloatingSettingsUiState = FloatingSettingsUiState(
            asrEnabled = false,
            onlyWhenImeVisible = false,
            holdToRecordEnabled = false,
            directDragEnabled = false,
            alphaPercent = 100f,
            sizeDp = 56,
            volumeKeyRecordingEnabled = false,
            volumeKeyRecordingMode = Prefs.VOLUME_KEY_MODE_UP_TOGGLE,
            volumeKeyStatusToastEnabled = false,
            shakeRecordingEnabled = false,
            shakeRecordingSensitivity = Prefs.ShakeRecordingSensitivity.DEFAULT.id,
            shakeRecordingSoundEnabled = false,
            writePasteEnabled = false,
            wakeWordEnabled = false
        )

        fun fromPrefs(prefs: Prefs): FloatingSettingsUiState = FloatingSettingsUiState(
            asrEnabled = prefs.floatingAsrEnabled,
            onlyWhenImeVisible = prefs.floatingSwitcherOnlyWhenImeVisible,
            holdToRecordEnabled = prefs.floatingBallHoldToRecordEnabled,
            directDragEnabled = prefs.floatingBallDirectDragEnabled,
            alphaPercent = (prefs.floatingSwitcherAlpha * 100f).coerceIn(30f, 100f),
            sizeDp = prefs.floatingBallSizeDp,
            volumeKeyRecordingEnabled = prefs.volumeKeyRecordingEnabled,
            volumeKeyRecordingMode = prefs.volumeKeyRecordingMode,
            volumeKeyStatusToastEnabled = prefs.volumeKeyStatusToastEnabled,
            shakeRecordingEnabled = prefs.shakeRecordingEnabled,
            shakeRecordingSensitivity = prefs.shakeRecordingSensitivity,
            shakeRecordingSoundEnabled = prefs.shakeRecordingSoundEnabled,
            writePasteEnabled = prefs.floatingWriteTextPasteEnabled,
            wakeWordEnabled = prefs.wakeWordEnabled
        )
    }
}

internal data class FloatingSettingsPrefsSnapshot(
    val uiState: FloatingSettingsUiState,
    val pastePackages: String
) {
    companion object {
        fun fromPrefs(prefs: Prefs): FloatingSettingsPrefsSnapshot = FloatingSettingsPrefsSnapshot(
            uiState = FloatingSettingsUiState.fromPrefs(prefs),
            pastePackages = prefs.floatingWritePastePackages
        )
    }
}

internal enum class FloatingPermissionRequest {
    Overlay,
    Accessibility
}

internal fun isAccessibilityServiceEnabled(context: Context): Boolean =
    HostA11yServiceResolver.isEnabledInSettings(context)

internal fun resetFloatingPosition(
    context: Context,
    prefs: Prefs,
    serviceManager: FloatingServiceManager
): Boolean {
    var success = true
    try {
        prefs.floatingBallPosX = -1
        prefs.floatingBallPosY = -1
        prefs.floatingBallDockSide = 0
        prefs.floatingBallDockFraction = -1f
        prefs.floatingBallDockHidden = false
    } catch (e: Throwable) {
        Log.e(FLOATING_SETTINGS_STATE_TAG, "Failed to reset floating position in prefs", e)
        success = false
    }
    try {
        serviceManager.resetAsrBallPosition()
    } catch (e: Throwable) {
        Log.e(FLOATING_SETTINGS_STATE_TAG, "Failed to dispatch reset to service", e)
        success = false
    }
    return success
}
