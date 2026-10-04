/**
 * 新手引导 Compose 弹窗与下载源选择。
 *
 * 归属模块：ui/setup/compose
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.setup.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDownloadSourceSheet
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun OnboardingDialogHost(
    state: OnboardingDialogState,
    onDismiss: () -> Unit,
    onConfirmOnlineGuide: () -> Unit,
    onSelectDownloadSource: (DownloadSourceOption) -> Unit
) {
    when (state) {
        OnboardingDialogState.None -> Unit
        OnboardingDialogState.OnlineGuide -> OnlineGuideDialog(
            onDismiss = onDismiss,
            onConfirm = onConfirmOnlineGuide
        )

        is OnboardingDialogState.DownloadSources -> SettingsDownloadSourceSheet(
            options = state.options,
            onDismiss = onDismiss,
            onSelect = onSelectDownloadSource
        )
    }
}

@Composable
private fun OnlineGuideDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = stringResource(R.string.model_guide_option_online)
    val message = stringResource(R.string.model_guide_online_dialog_message)
    val confirm = stringResource(R.string.btn_get_api_key_guide)
    val cancel = stringResource(R.string.btn_cancel)
    val exit = rememberSettingsDialogExitController()
    fun finishDismiss() {
        exit.finish()
    }
    OverlayDialog(
        show = exit.show,
        title = title,
        summary = message,
        onDismissRequest = { exit.dismiss(onDismiss) },
        onDismissFinished = ::finishDismiss
    ) {
        SettingsDialogActionRow(
            actions = listOf(
                SettingsDialogAction(
                    text = cancel,
                    onClick = { exit.dismiss(onDismiss) }
                ),
                SettingsDialogAction(
                    text = confirm,
                    onClick = { exit.dismiss(onConfirm) },
                    primary = true
                )
            )
        )
    }
}
