package com.brycewg.asrkb.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.HostA11yServiceResolver
import com.brycewg.asrkb.ui.floating.floatingInputNeedsAccessibility

/**
 * 一键设置流程的状态机管理器
 *
 * 负责管理设置流程的状态转换、权限检查和用户交互
 */
class SetupStateMachine(
    private val context: Context,
    private val showMessage: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "SetupStateMachine"
    }

    var currentState: SetupState = SetupState.NotStarted
        private set

    private val prefs = Prefs(context)

    /**
     * 推进状态机到下一个状态
     *
     * 根据当前状态和系统状态（输入法是否启用/选择、权限是否授予）决定下一步操作
     *
     * @return 下一个状态，如果无法继续则返回当前状态
     */
    fun advance(): SetupState {
        Log.d(TAG, "Advancing from state: $currentState")

        currentState = when (val state = currentState) {
            is SetupState.NotStarted -> {
                val needOptional = hasMissingOptionalPermissions()
                if (!hasAllRequiredPermissions() || needOptional) {
                    // 必需权限或可选权限（如通知权限）缺失，先请求权限
                    SetupState.RequestingPermissions()
                } else {
                    SetupState.Completed
                }
            }

            is SetupState.RequestingPermissions -> {
                if (hasAllRequiredPermissions()) {
                    // 所有权限已授予
                    Log.d(TAG, "All permissions granted, setup completed")
                    SetupState.Completed
                } else {
                    // 继续请求缺失的权限
                    state
                }
            }

            is SetupState.Completed, is SetupState.Aborted -> {
                // 终态，不再转换
                state
            }
        }

        Log.d(TAG, "Advanced to state: $currentState")
        return currentState
    }

    /**
     * 执行当前状态对应的操作（弹出系统设置、请求权限等）
     *
     * @return true 表示执行了操作，false 表示当前状态无需操作
     */
    fun executeCurrentStateAction(): Boolean {
        Log.d(TAG, "Executing action for state: $currentState")

        return when (val state = currentState) {
            is SetupState.RequestingPermissions -> {
                // 按顺序请求缺失的权限
                requestNextMissingPermission(state)
            }

            is SetupState.Completed -> {
                // 显示完成提示
                showSetupMessage(R.string.toast_setup_all_done)
                false
            }

            is SetupState.Aborted -> {
                // 显示中止提示
                showMessage(context.getString(R.string.toast_setup_aborted, state.reason))
                false
            }

            is SetupState.NotStarted -> {
                // 初始状态，无需操作
                false
            }
        }
    }

    /**
     * 请求下一个缺失的权限
     *
     * 按照优先级顺序请求：麦克风 > 悬浮窗 > 通知 > 无障碍
     *
     * @return true 表示发起了权限请求，false 表示所有权限已授予或已全部请求过
     */
    private fun requestNextMissingPermission(state: SetupState.RequestingPermissions): Boolean {
        // 1) 麦克风权限（必需）
        if (!state.askedMic && !hasMicrophonePermission()) {
            Log.d(TAG, "Requesting microphone permission")
            currentState = state.copy(askedMic = true)
            return true
        }

        // 2) 悬浮窗权限（当启用悬浮功能时需要）
        val needOverlay = prefs.floatingAsrEnabled
        if (!state.askedOverlay && needOverlay && !hasOverlayPermission()) {
            Log.d(TAG, "Requesting overlay permission")
            showSetupMessage(R.string.toast_need_overlay_perm)
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
                currentState = state.copy(askedOverlay = true)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request overlay permission", e)
                currentState = state.copy(askedOverlay = true)
                return false
            }
        }

        // 3) 通知权限（Android 13+，增强项）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!state.askedNotif && !hasNotificationPermission()) {
                Log.d(TAG, "Requesting notification permission")
                // 注意：实际请求需要通过 Activity.requestPermissions()
                // 这里只更新状态，实际请求由 Activity 处理
                currentState = state.copy(askedNotif = true)
                return true
            }
        }

        // 4) 无障碍权限（音量键始终依赖；悬浮球在输入法桥接关闭时依赖）
        val needA11y = floatingInputNeedsAccessibility(
            floatingEnabled = prefs.floatingAsrEnabled,
            volumeKeyEnabled = prefs.volumeKeyRecordingEnabled,
            shakeRecordingEnabled = prefs.shakeRecordingEnabled
        )
        if (!state.askedA11y && needA11y && !hasAccessibilityPermission()) {
            Log.d(TAG, "Requesting accessibility permission")
            showSetupMessage(R.string.toast_need_accessibility_perm)
            try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                currentState = state.copy(askedA11y = true)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request accessibility permission", e)
                currentState = state.copy(askedA11y = true)
                return false
            }
        }

        // 所有权限都已请求过或已授予
        Log.d(TAG, "All permissions requested or granted")
        return false
    }

    /**
     * 重置状态机到初始状态
     */
    fun reset() {
        Log.d(TAG, "Resetting state machine")
        currentState = SetupState.NotStarted
    }

    private fun showSetupMessage(messageRes: Int) {
        showMessage(context.getString(messageRes))
    }

    /**
     * 检查是否所有必需权限已授予
     */
    private fun hasAllRequiredPermissions(): Boolean {
        val micGranted = hasMicrophonePermission()
        val needOverlay = prefs.floatingAsrEnabled
        val overlayGranted = !needOverlay || hasOverlayPermission()
        val needA11y = floatingInputNeedsAccessibility(
            floatingEnabled = prefs.floatingAsrEnabled,
            volumeKeyEnabled = prefs.volumeKeyRecordingEnabled,
            shakeRecordingEnabled = prefs.shakeRecordingEnabled
        )
        val a11yGranted = !needA11y || hasAccessibilityPermission()

        // Android 13+ 通知权限仅作为增强项，缺失不阻塞核心功能
        return micGranted && overlayGranted && a11yGranted
    }

    /**
     * 是否存在缺失的“可选权限”。
     * 目前仅包含 Android 13+ 的通知权限，用于在一键设置中补询。
     */
    private fun hasMissingOptionalPermissions(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()

    private fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    private fun hasNotificationPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true // Android 13 以下默认已授予
    }

    private fun hasAccessibilityPermission(): Boolean =
        HostA11yServiceResolver.isEnabledInSettings(context)

    /**
     * 获取当前状态对应的 RequestingPermissions，如果不是则返回 null
     * 用于从 Activity 的 onRequestPermissionsResult 回调中更新状态
     */
    fun getCurrentPermissionState(): SetupState.RequestingPermissions? = currentState as? SetupState.RequestingPermissions
}
