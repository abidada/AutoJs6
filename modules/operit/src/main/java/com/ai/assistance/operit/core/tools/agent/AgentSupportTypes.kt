package com.ai.assistance.operit.core.tools.agent

import android.content.Context
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShizukuAuthorizer
import com.ai.assistance.operit.data.preferences.AndroidPermissionPreferences
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager

/**
 * Operit port: AgentConfig / StepResult / ParsedAgentAction / PrivilegedExecutionState were
 * upstream members of PhoneAgent.kt (virtual-screen subagent, trimmed). Extracted verbatim so
 * AutoGlmViewModel and other referencing files keep compiling against upstream diffs.
 */
/** Configuration for the PhoneAgent. */
data class AgentConfig(
    val maxSteps: Int = 20
)

/** Result of a single agent step. */
data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val action: ParsedAgentAction?,
    val thinking: String?,
    val message: String? = null
)

/** Parsed action from the model's response. */
data class ParsedAgentAction(
    val metadata: String,
    val actionName: String?,
    val fields: Map<String, String>
)

private data class PrivilegedExecutionState(
    val isAdbOrHigher: Boolean,
    val hasDebuggerShizukuAccess: Boolean
)

private fun resolvePrivilegedExecutionState(
    context: Context,
    androidPermissionPreferences: AndroidPermissionPreferences,
    checkDebuggerShizuku: Boolean = true,
    onExperimentalFlagReadError: ((Exception) -> Unit)? = null
): PrivilegedExecutionState {
    val preferredLevel = androidPermissionPreferences.getPreferredPermissionLevel()
        ?: AndroidPermissionLevel.STANDARD

    var isAdbOrHigher = when (preferredLevel) {
        AndroidPermissionLevel.DEBUGGER,
        AndroidPermissionLevel.ADMIN,
        AndroidPermissionLevel.ROOT -> true
        else -> false
    }

    if (isAdbOrHigher) {
        val experimentalEnabled = try {
            DisplayPreferencesManager.getInstance(context).isExperimentalVirtualDisplayEnabled()
        } catch (e: Exception) {
            onExperimentalFlagReadError?.invoke(e)
            true
        }
        if (!experimentalEnabled) {
            isAdbOrHigher = false
        }
    }

    val hasDebuggerShizukuAccess = if (checkDebuggerShizuku &&
        isAdbOrHigher &&
        preferredLevel == AndroidPermissionLevel.DEBUGGER
    ) {
        val isShizukuRunning = ShizukuAuthorizer.isShizukuServiceRunning()
        val hasShizukuPermission = if (isShizukuRunning) ShizukuAuthorizer.hasShizukuPermission() else false
        isShizukuRunning && hasShizukuPermission
    } else {
        true
    }

    return PrivilegedExecutionState(
        isAdbOrHigher = isAdbOrHigher,
        hasDebuggerShizukuAccess = hasDebuggerShizukuAccess
    )
}
