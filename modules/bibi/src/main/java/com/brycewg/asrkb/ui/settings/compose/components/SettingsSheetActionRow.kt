/**
 * Compose 设置底部弹层操作按钮行。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

@Composable
internal fun SettingsSheetActionRow(
    cancelText: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = SettingsLayoutMetrics.SheetHorizontalPadding,
        vertical = SettingsLayoutMetrics.SheetBottomPadding
    )
) {
    val hapticTap = LocalSettingsHapticTap.current
    val dismissWithHaptic = {
        hapticTap()
        onDismiss()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SheetActionButtonSpacing)
    ) {
        MiuixTextButton(
            text = cancelText,
            onClick = dismissWithHaptic,
            modifier = Modifier.weight(1f)
        )
        SettingsActionButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f)
        )
    }
}
