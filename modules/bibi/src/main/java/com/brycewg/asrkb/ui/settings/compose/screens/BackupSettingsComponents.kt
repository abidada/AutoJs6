/**
 * Compose 备份设置页的纯 UI 组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun BackupScaffold(
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    SettingsDetailScaffold(
        titleRes = R.string.title_backup_settings,
        onBack = onBack,
        content = content
    )
}

@Composable
internal fun BackupSection(
    titleRes: Int,
    content: @Composable () -> Unit
) {
    SettingsSectionContainer(titleRes = titleRes) {
        content()
    }
}

@Composable
internal fun BackupBodyText(text: String) {
    MiuixText(
        text = text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1
    )
}

@Composable
internal fun BackupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    password: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    index: Int = 0,
    count: Int = 1
) {
    SettingsTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        password = password,
        singleLine = true,
        keyboardType = keyboardType,
        visualTransformation = if (password) null else visualTransformation,
        index = index,
        count = count
    )
}

@Composable
internal fun BackupActionPreference(
    id: String,
    titleRes: Int,
    icon: ImageVector,
    enabled: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit
) {
    SettingsPreference(
        entry = SettingsEntry.Action(
            id = id,
            titleRes = titleRes,
            icon = icon,
            enabled = enabled,
            onClick = onClick
        ),
        index = index,
        count = count
    )
}
