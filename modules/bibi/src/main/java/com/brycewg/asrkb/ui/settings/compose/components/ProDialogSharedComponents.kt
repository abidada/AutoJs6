/**
 * Pro 相关 Compose 弹窗共享组件。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProDialogSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        content()
    }
}

@Composable
internal fun Modifier.proDialogScrollableContent(): Modifier {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.82f
    return fillMaxWidth()
        .heightIn(max = maxHeight)
        .verticalScroll(rememberScrollState())
}

@Composable
internal fun DialogTitle(text: String) {
    MiuixText(
        text = text,
        fontSize = MiuixTheme.textStyles.title2.fontSize,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurface
    )
}

@Composable
internal fun DialogSectionLabel(text: String) {
    MiuixText(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontWeight = FontWeight.Medium
    )
}

@Composable
internal fun DialogBody(
    text: String,
    primary: Boolean = false,
    textAlign: TextAlign? = null
) {
    MiuixText(
        text = text,
        color = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        textAlign = textAlign
    )
}

@Composable
internal fun DialogCaption(
    text: String,
    textAlign: TextAlign? = null
) {
    MiuixText(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = textAlign
    )
}

@Composable
internal fun DialogPrimaryAction(
    text: String,
    onClick: () -> Unit
) {
    val clickWithHaptic = dialogClickWithHaptic(onClick)
    MiuixButton(
        onClick = clickWithHaptic,
        modifier = Modifier.fillMaxWidth(),
        colors = MiuixButtonDefaults.buttonColorsPrimary()
    ) {
        MiuixText(text)
    }
}

@Composable
internal fun DialogTonalAction(
    text: String,
    onClick: () -> Unit
) {
    val clickWithHaptic = dialogClickWithHaptic(onClick)
    MiuixButton(
        onClick = clickWithHaptic,
        modifier = Modifier.fillMaxWidth()
    ) {
        MiuixText(text)
    }
}

@Composable
internal fun DialogTextAction(
    text: String,
    onClick: () -> Unit
) {
    val clickWithHaptic = dialogClickWithHaptic(onClick)
    MiuixTextButton(
        text = text,
        onClick = clickWithHaptic,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun dialogClickWithHaptic(onClick: () -> Unit): () -> Unit {
    val hapticTap = LocalSettingsHapticTap.current
    return {
        hapticTap()
        onClick()
    }
}
