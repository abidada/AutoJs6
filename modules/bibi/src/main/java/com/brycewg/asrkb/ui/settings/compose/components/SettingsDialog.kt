/**
 * Compose 设置消息与进度弹窗。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class SettingsMessageDialogState(
    val title: String,
    val message: String,
    val confirmText: String,
    val dismissText: String? = null,
    val onDismissAction: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null
)

internal data class SettingsProgressDialogState(
    val message: String,
    val cancelText: String? = null,
    val onCancel: (() -> Unit)? = null
)

internal data class SettingsLongTextDialogState(
    val title: String,
    val text: String,
    val confirmText: String
)

@Composable
internal fun SettingsMessageDialog(
    state: SettingsMessageDialogState?,
    onDismiss: () -> Unit
) {
    val visibleState = state ?: return
    var show by remember(visibleState) { mutableStateOf(true) }
    var afterDismiss by remember(visibleState) { mutableStateOf<(() -> Unit)?>(null) }
    val dismiss: (() -> Unit) -> Unit = { action ->
        if (show) {
            afterDismiss = action
            show = false
        }
    }
    MiuixMessageDialog(
        state = visibleState,
        show = show,
        onDismiss = { dismiss {} },
        onDismissAfter = dismiss,
        onDismissFinished = {
            afterDismiss?.invoke()
            onDismiss()
        }
    )
}

@Composable
internal fun SettingsProgressDialog(
    state: SettingsProgressDialogState?,
    onDismiss: () -> Unit
) {
    val visibleState = state ?: return
    MiuixProgressDialog(visibleState, onDismiss)
}

@Composable
internal fun SettingsLongTextDialog(
    state: SettingsLongTextDialogState?,
    onDismiss: () -> Unit
) {
    val visibleState = state ?: return
    var show by remember(visibleState) { mutableStateOf(true) }
    fun dismissWithAnimation() {
        if (!show) return
        show = false
    }
    MiuixLongTextDialog(
        state = visibleState,
        show = show,
        onDismiss = ::dismissWithAnimation,
        onDismissFinished = onDismiss
    )
}

@Composable
private fun MiuixMessageDialog(
    state: SettingsMessageDialogState,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissAfter: (() -> Unit) -> Unit,
    onDismissFinished: () -> Unit
) {
    OverlayDialog(
        show = show,
        title = state.title,
        summary = state.message,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished
    ) {
        SettingsDialogActionRow(
            actions = listOfNotNull(
                state.dismissText?.let { dismissText ->
                    SettingsDialogAction(
                        text = dismissText,
                        onClick = {
                            onDismissAfter { state.onDismissAction?.invoke() }
                        }
                    )
                },
                SettingsDialogAction(
                    text = state.confirmText,
                    onClick = {
                        onDismissAfter { state.onConfirm?.invoke() }
                    },
                    primary = true
                )
            )
        )
    }
}

@Composable
private fun MiuixLongTextDialog(
    state: SettingsLongTextDialogState,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit
) {
    OverlayDialog(
        show = show,
        title = state.title,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished
    ) {
        LicenseTextContent(
            text = state.text,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
                .padding(bottom = SettingsLayoutMetrics.DialogContentBottomPadding)
        )
        SettingsDialogActionRow(
            actions = listOf(
                SettingsDialogAction(
                    text = state.confirmText,
                    onClick = onDismiss,
                    primary = true
                )
            )
        )
    }
}

@Composable
private fun MiuixProgressDialog(
    state: SettingsProgressDialogState,
    onDismiss: () -> Unit
) {
    OverlayDialog(
        show = true,
        onDismissRequest = null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    bottom = if (state.cancelText == null) {
                        0.dp
                    } else {
                        SettingsLayoutMetrics.DialogContentBottomPadding
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InfiniteProgressIndicator(
                color = MiuixTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(SettingsLayoutMetrics.DialogProgressMiuixSpacing))
            MiuixText(
                text = state.message,
                fontWeight = FontWeight.Medium
            )
        }
        state.cancelText?.let { cancelText ->
            SettingsDialogActionRow(
                actions = listOf(
                    SettingsDialogAction(
                        text = cancelText,
                        onClick = {
                            state.onCancel?.invoke() ?: onDismiss()
                        }
                    )
                )
            )
        }
    }
}

@Composable
private fun LicenseTextContent(
    text: String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    SelectionContainer {
        MiuixText(
            text = text,
            modifier = modifier.verticalScroll(scrollState),
            style = MiuixTheme.textStyles.body2,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}
