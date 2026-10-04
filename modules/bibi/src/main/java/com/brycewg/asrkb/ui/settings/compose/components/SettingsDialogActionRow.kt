/**
 * Compose 设置弹窗操作按钮行。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

internal data class SettingsDialogAction(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val primary: Boolean = false
)

@Composable
internal fun SettingsDialogActionRow(
    actions: List<SettingsDialogAction>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    spacing: Dp = SettingsLayoutMetrics.DialogActionButtonSpacing
) {
    if (actions.isEmpty()) return
    val rowModifier = modifier
        .fillMaxWidth()
        .padding(contentPadding)
    if (actions.size > 2) {
        Column(
            modifier = rowModifier,
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.ActionButtonSpacing)
        ) {
            actions.forEach { action ->
                SettingsDialogActionButton(
                    action = action,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        return
    }
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        actions.forEach { action ->
            SettingsDialogActionButton(
                action = action,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SettingsDialogActionButton(
    action: SettingsDialogAction,
    modifier: Modifier
) {
    val hapticTap = LocalSettingsHapticTap.current
    val clickWithHaptic = {
        hapticTap()
        action.onClick()
    }
    MiuixTextButton(
        text = action.text,
        onClick = clickWithHaptic,
        enabled = action.enabled,
        modifier = modifier,
        colors = if (action.primary) {
            MiuixButtonDefaults.textButtonColorsPrimary()
        } else {
            MiuixButtonDefaults.textButtonColors()
        }
    )
}
