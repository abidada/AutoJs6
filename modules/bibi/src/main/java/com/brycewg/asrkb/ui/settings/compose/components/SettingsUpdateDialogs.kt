/**
 * Compose 设置页更新检查弹窗与下载源选择。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.update.UpdateChecker
import java.text.SimpleDateFormat
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal sealed interface SettingsUpdateUiState {
    data object Idle : SettingsUpdateUiState
    data object Checking : SettingsUpdateUiState
    data class UpdateAvailable(val result: UpdateChecker.UpdateCheckResult) : SettingsUpdateUiState
    data class CurrentVersion(val result: UpdateChecker.UpdateCheckResult) : SettingsUpdateUiState
    data class CheckFailed(val message: String) : SettingsUpdateUiState
    data class Message(
        val title: String,
        val message: String
    ) : SettingsUpdateUiState

    data class DownloadSources(
        val version: String,
        val options: List<DownloadSourceOption>
    ) : SettingsUpdateUiState
}

@Composable
internal fun SettingsUpdateHost(
    state: SettingsUpdateUiState,
    onDismiss: () -> Unit,
    onDownload: (UpdateChecker.UpdateCheckResult) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onOpenChangelog: () -> Unit,
    onManualCheck: () -> Unit,
    onSelectDownloadSource: (DownloadSourceOption, String) -> Unit
) {
    when (state) {
        SettingsUpdateUiState.Idle -> Unit
        SettingsUpdateUiState.Checking -> SettingsProgressDialog(
            state = SettingsProgressDialogState(stringResource(R.string.update_checking)),
            onDismiss = onDismiss
        )

        is SettingsUpdateUiState.UpdateAvailable -> UpdateResultDialog(
            result = state.result,
            hasUpdate = true,
            onDismiss = onDismiss,
            onDownload = { onDownload(state.result) },
            onOpenReleasePage = { onOpenReleasePage(state.result.downloadUrl) },
            onOpenChangelog = onOpenChangelog
        )

        is SettingsUpdateUiState.CurrentVersion -> UpdateResultDialog(
            result = state.result,
            hasUpdate = false,
            onDismiss = onDismiss,
            onDownload = { },
            onOpenReleasePage = { onOpenReleasePage(state.result.downloadUrl) },
            onOpenChangelog = onOpenChangelog
        )

        is SettingsUpdateUiState.CheckFailed -> UpdateFailedDialog(
            message = state.message,
            onDismiss = onDismiss,
            onManualCheck = onManualCheck
        )

        is SettingsUpdateUiState.Message -> SettingsMessageDialog(
            state = SettingsMessageDialogState(
                title = state.title,
                message = state.message,
                confirmText = stringResource(android.R.string.ok)
            ),
            onDismiss = onDismiss
        )

        is SettingsUpdateUiState.DownloadSources -> SettingsDownloadSourceSheet(
            options = state.options,
            onDismiss = onDismiss,
            onSelect = { option -> onSelectDownloadSource(option, state.version) }
        )
    }
}

@Composable
private fun UpdateResultDialog(
    result: UpdateChecker.UpdateCheckResult,
    hasUpdate: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onOpenChangelog: () -> Unit
) {
    val exit = rememberSettingsDialogExitController(result)
    fun finishDismiss() {
        exit.finish()
    }
    val title = stringResource(
        if (hasUpdate) R.string.update_dialog_title else R.string.current_version_info_title
    )
    val primaryText = stringResource(if (hasUpdate) R.string.btn_download else android.R.string.ok)
    val primaryAction: () -> Unit = if (hasUpdate) {
        { exit.dismiss(onDownload) }
    } else {
        { exit.dismiss(onDismiss) }
    }
    OverlayDialog(
        show = exit.show,
        title = title,
        onDismissRequest = { exit.dismiss(onDismiss) },
        onDismissFinished = ::finishDismiss
    ) {
        UpdateMessageContent(
            result = result,
            hasUpdate = hasUpdate,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        SettingsDialogActionRow(
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.btn_view_changelog),
                    onClick = onOpenChangelog
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.btn_view_release_page),
                    onClick = onOpenReleasePage
                ),
                SettingsDialogAction(
                    text = primaryText,
                    onClick = primaryAction,
                    primary = true
                )
            )
        )
    }
}

@Composable
private fun UpdateFailedDialog(
    message: String,
    onDismiss: () -> Unit,
    onManualCheck: () -> Unit
) {
    val title = stringResource(R.string.update_check_failed, message.ifBlank { "Unknown error" })
    val exit = rememberSettingsDialogExitController(title)
    fun finishDismiss() {
        exit.finish()
    }
    OverlayDialog(
        show = exit.show,
        title = title,
        onDismissRequest = { exit.dismiss(onDismiss) },
        onDismissFinished = ::finishDismiss
    ) {
        SettingsDialogActionRow(
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.btn_cancel),
                    onClick = { exit.dismiss(onDismiss) }
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.btn_manual_check),
                    onClick = onManualCheck,
                    primary = true
                )
            )
        )
    }
}

@Composable
private fun UpdateMessageContent(
    result: UpdateChecker.UpdateCheckResult,
    hasUpdate: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        UpdateText(
            text = if (hasUpdate) {
                context.getString(
                    R.string.update_dialog_message,
                    result.currentVersion,
                    result.latestVersion
                )
            } else {
                context.getString(R.string.current_version_message, result.currentVersion)
            }
        )
        result.importantNotice?.let { notice ->
            UpdateText(
                text = notice,
                colorRole = result.noticeLevel,
                fontWeight = FontWeight.Bold
            )
        }
        result.updateTime?.let { updateTime ->
            UpdateText(
                text = stringResource(R.string.update_timestamp_label, formatUpdateTime(context, updateTime))
            )
        }
        result.releaseNotes?.let { notes ->
            UpdateText(
                text = stringResource(R.string.update_release_notes_label, notes)
            )
        }
    }
}

@Composable
private fun UpdateText(
    text: String,
    modifier: Modifier = Modifier,
    colorRole: UpdateChecker.NoticeLevel? = null,
    fontWeight: FontWeight? = null
) {
    MiuixText(
        text = text,
        modifier = modifier,
        color = when (colorRole) {
            UpdateChecker.NoticeLevel.INFO -> MiuixTheme.colorScheme.primary
            UpdateChecker.NoticeLevel.WARNING -> MiuixTheme.colorScheme.secondary
            UpdateChecker.NoticeLevel.CRITICAL -> MiuixTheme.colorScheme.error
            null -> MiuixTheme.colorScheme.onSurface
        },
        style = MiuixTheme.textStyles.body2,
        fontWeight = fontWeight
    )
}

private fun formatUpdateTime(context: android.content.Context, updateTime: String): String = try {
    val locale = LocaleHelper.locale(context)
    val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", locale)
    utcFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
    val date = utcFormat.parse(updateTime)

    val localFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
    if (date != null) localFormat.format(date) else updateTime
} catch (_: Exception) {
    updateTime
}
