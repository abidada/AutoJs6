/**
 * Compose 设置相关芯片组件。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SettingsChipShape = RoundedCornerShape(50)
private val SettingsChipIconSize = 18.dp
private val SettingsChipMinHeight = 32.dp
private val SettingsChipHorizontalPadding = 12.dp
private val SettingsChipVerticalPadding = 7.dp
private val SettingsChipIconSpacing = 6.dp

@Composable
internal fun SettingsAssistChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val hapticTap = LocalSettingsHapticTap.current
    val clickWithHaptic = onClick?.let { action ->
        {
            hapticTap()
            action()
        }
    }
    SettingsMiuixChip(
        label = label,
        modifier = modifier,
        icon = icon,
        onClick = clickWithHaptic
    )
}

@Composable
internal fun SettingsFilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val clickWithHaptic = {
        hapticTap()
        onClick()
    }
    SettingsMiuixChip(
        label = label,
        selected = selected,
        modifier = modifier,
        onClick = clickWithHaptic
    )
}

@Composable
private fun SettingsMiuixChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val backgroundColor = when {
        selected -> MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> MiuixTheme.colorScheme.secondaryVariant
    }
    val contentColor = when {
        selected -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurface
    }
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .heightIn(min = SettingsChipMinHeight)
            .clip(SettingsChipShape)
            .background(backgroundColor)
            .then(clickableModifier)
            .padding(
                horizontal = SettingsChipHorizontalPadding,
                vertical = SettingsChipVerticalPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(SettingsChipIconSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            MiuixIcon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(SettingsChipIconSize),
                tint = contentColor
            )
        }
        MiuixText(
            text = label,
            color = contentColor,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
