/**
 * 主题感知文本:使用 Miuix 文本组件与颜色,保证日间/夜间模式可读。
 *
 * 归属模块:ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsThemedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified
) {
    MiuixText(
        text = text,
        modifier = modifier,
        style = style,
        color = if (color == Color.Unspecified) {
            MiuixTheme.colorScheme.onBackground
        } else {
            color
        }
    )
}
