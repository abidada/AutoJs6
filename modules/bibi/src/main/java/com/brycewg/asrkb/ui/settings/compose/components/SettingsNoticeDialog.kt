/**
 * Compose 设置页轻量说明弹窗：多段正文 + 可选「下次不再提醒」。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class SettingsNoticeDialogState(
    val title: String,
    val paragraphs: List<String>,
    val dontShowAgainText: String?,
    val confirmText: String?,
    val dismissText: String,
    val onDontShowAgain: () -> Unit,
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {}
)

@Composable
internal fun SettingsNoticeDialog(
    state: SettingsNoticeDialogState?,
    onDismiss: () -> Unit
) {
    val visibleState = state ?: return
    var dontShowAgain by remember(visibleState) { mutableStateOf(false) }
    val exit = rememberSettingsDialogExitController(visibleState)

    fun finishDismiss() {
        exit.finish()
    }

    fun confirm() {
        exit.dismiss {
            if (dontShowAgain) visibleState.onDontShowAgain()
            visibleState.onConfirm()
            onDismiss()
        }
    }

    fun cancel() {
        exit.dismiss {
            if (dontShowAgain) visibleState.onDontShowAgain()
            visibleState.onCancel()
            onDismiss()
        }
    }

    fun dismissByScrim() {
        exit.dismiss {
            visibleState.onCancel()
            onDismiss()
        }
    }

    val actions = buildList {
        if (visibleState.confirmText != null) {
            add(
                SettingsDialogAction(
                    text = visibleState.dismissText,
                    onClick = ::cancel
                )
            )
            add(
                SettingsDialogAction(
                    text = visibleState.confirmText,
                    onClick = ::confirm,
                    primary = true
                )
            )
        } else {
            add(
                SettingsDialogAction(
                    text = visibleState.dismissText,
                    onClick = ::cancel,
                    primary = true
                )
            )
        }
    }

    OverlayDialog(
        show = exit.show,
        title = visibleState.title,
        onDismissRequest = ::dismissByScrim,
        onDismissFinished = ::finishDismiss
    ) {
        NoticeDialogContent(
            state = visibleState,
            dontShowAgain = dontShowAgain,
            onDontShowAgainChange = { dontShowAgain = it },
            modifier = Modifier.padding(bottom = SettingsLayoutMetrics.DialogContentBottomPadding)
        )
        SettingsDialogActionRow(
            actions = actions
        )
    }
}

@Composable
private fun NoticeDialogContent(
    state: SettingsNoticeDialogState,
    dontShowAgain: Boolean,
    onDontShowAgainChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val paragraphs = state.paragraphs.filter { it.isNotBlank() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        paragraphs.forEachIndexed { index, paragraph ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerSectionSpacing))
            }
            NoticeBodyText(text = paragraph)
        }
        state.dontShowAgainText?.let { text ->
            Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerDontShowSpacing))
            DontShowAgainRow(
                text = text,
                checked = dontShowAgain,
                onCheckedChange = onDontShowAgainChange
            )
        }
    }
}

@Composable
private fun NoticeBodyText(
    text: String,
    modifier: Modifier = Modifier
) {
    MiuixText(
        text = text,
        modifier = modifier,
        color = MiuixTheme.colorScheme.onSurface,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Normal
    )
}
