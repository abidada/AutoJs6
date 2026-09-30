/**
 * 主题感知文本:按界面风格(Miuix/Material)选择文本组件与颜色,保证日间/夜间模式可读。
 *
 * 归属模块:ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalBibiUiMode
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsThemedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified
) {
    if (LocalBibiUiMode.current == BibiUiMode.Miuix) {
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
    } else {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = if (color == Color.Unspecified) {
                MaterialTheme.colorScheme.onBackground
            } else {
                color
            }
        )
    }
}
