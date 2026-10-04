/**
 * ASR 设置页弹窗与底部弹层宿主。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMultiChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMultiChoiceSheetState

@Composable
internal fun AsrSettingsDialogHost(
    choiceSheet: SettingsChoiceSheetState?,
    multiChoiceSheet: SettingsMultiChoiceSheetState?,
    featureExplainerDialog: SettingsFeatureExplainerDialogState?,
    messageDialog: SettingsMessageDialogState?,
    onDismissChoiceSheet: () -> Unit,
    onDismissMultiChoiceSheet: () -> Unit,
    onDismissFeatureExplainerDialog: () -> Unit,
    onDismissMessageDialog: () -> Unit
) {
    SettingsChoiceSheet(
        state = choiceSheet,
        onDismiss = onDismissChoiceSheet
    )
    SettingsMultiChoiceSheet(
        state = multiChoiceSheet,
        onDismiss = onDismissMultiChoiceSheet
    )
    SettingsFeatureExplainerDialog(
        state = featureExplainerDialog,
        onDismiss = onDismissFeatureExplainerDialog
    )
    SettingsMessageDialog(
        state = messageDialog,
        onDismiss = onDismissMessageDialog
    )
}
