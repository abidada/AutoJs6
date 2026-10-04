/**
 * Compose 设置页点击型值展示项。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.preference.ArrowPreference as MiuixArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsValuePreference(
    @StringRes titleRes: Int,
    value: String,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    trailingActionIcon: ImageVector? = null,
    @StringRes trailingActionContentDescriptionRes: Int? = null,
    onTrailingActionClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val clickWithHaptic = {
        hapticTap()
        onClick()
    }
    val trailingClickWithHaptic = onTrailingActionClick?.let { action ->
        {
            hapticTap()
            action()
        }
    }
    MiuixArrowPreference(
        title = stringResource(titleRes),
        summary = value,
        endActions = {
            if (trailingActionIcon != null && trailingClickWithHaptic != null) {
                MiuixIconButton(
                    onClick = trailingClickWithHaptic,
                    modifier = Modifier.size(36.dp),
                    minWidth = 36.dp,
                    minHeight = 36.dp
                ) {
                    MiuixIcon(
                        imageVector = trailingActionIcon,
                        contentDescription = trailingActionContentDescriptionRes?.let { stringResource(it) },
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            }
        },
        onClick = clickWithHaptic
    )
}
