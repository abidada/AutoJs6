/**
 * Compose 设置分区容器。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsSectionContainer(
    @StringRes titleRes: Int? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        titleRes?.let { SettingsSectionTitle(stringResource(it)) }
        MiuixCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(vertical = SettingsLayoutMetrics.SectionContainerVerticalPadding),
                content = content
            )
        }
    }
}

@Composable
internal fun SettingsSectionTitle(text: String) {
    MiuixText(
        text = text,
        modifier = Modifier.padding(
            horizontal = SettingsLayoutMetrics.DetailSectionTitleHorizontalPadding,
            vertical = SettingsLayoutMetrics.DetailSectionTitleVerticalPadding
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1
    )
}
