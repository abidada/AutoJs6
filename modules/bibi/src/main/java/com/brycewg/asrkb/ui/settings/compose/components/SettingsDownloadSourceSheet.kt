/**
 * Compose 下载源选择底部弹层。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.DownloadSourceLatencyResult
import com.brycewg.asrkb.ui.DownloadSourceLatencyStatus
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.buildDownloadSourceAddressDisplay
import com.brycewg.asrkb.ui.measureDownloadSourceLatency
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsDownloadSourceSheet(
    options: List<DownloadSourceOption>,
    onDismiss: () -> Unit,
    onSelect: (DownloadSourceOption) -> Unit
) {
    if (options.isEmpty()) return
    var show by remember(options) { mutableStateOf(true) }
    var selectedOption by remember(options) { mutableStateOf<DownloadSourceOption?>(null) }
    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.download_source_title),
        onDismissRequest = { show = false },
        onDismissFinished = {
            selectedOption?.let(onSelect)
            onDismiss()
        }
    ) {
        DownloadSourceContent(
            options = options,
            onSelect = { option ->
                selectedOption = option
                show = false
            }
        )
    }
}

@Composable
private fun DownloadSourceContent(
    options: List<DownloadSourceOption>,
    onSelect: (DownloadSourceOption) -> Unit,
    modifier: Modifier = Modifier
) {
    val latencyResults = remember(options) { mutableStateMapOf<String, DownloadSourceLatencyResult>() }
    val hapticTap = LocalSettingsHapticTap.current

    LaunchedEffect(options) {
        options.forEach { option ->
            latencyResults[option.url] = DownloadSourceLatencyResult(DownloadSourceLatencyStatus.Pending)
            launch {
                latencyResults[option.url] = measureDownloadSourceLatency(option.url)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSheetLazyColumn(
            contentPadding = PaddingValues(bottom = SettingsLayoutMetrics.SheetBottomPadding)
        ) {
            itemsIndexed(options, key = { _, option -> option.url }) { _, option ->
                DownloadSourceRow(
                    option = option,
                    latency = latencyResults[option.url]
                        ?: DownloadSourceLatencyResult(DownloadSourceLatencyStatus.Pending),
                    onClick = {
                        hapticTap()
                        onSelect(option)
                    }
                )
            }
            item("latency-note") {
                DownloadSourceText(
                    text = stringResource(R.string.download_source_latency_note),
                    modifier = Modifier.padding(
                        horizontal = SettingsLayoutMetrics.SheetSupportingTextHorizontalPadding,
                        vertical = SettingsLayoutMetrics.SheetSupportingTextVerticalPadding
                    )
                )
            }
        }
    }
}

@Composable
private fun DownloadSourceRow(
    option: DownloadSourceOption,
    latency: DownloadSourceLatencyResult,
    onClick: () -> Unit
) {
    val latencyText = latency.asText()
    val address = buildDownloadSourceAddressDisplay(option.url)
    BasicComponent(
        title = option.label,
        summary = address,
        insideMargin = PaddingValues(
            horizontal = SettingsLayoutMetrics.DownloadSourceInsideHorizontalPadding,
            vertical = SettingsLayoutMetrics.DownloadSourceInsideVerticalPadding
        ),
        onClick = onClick,
        endActions = {
            MiuixText(
                text = latencyText,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    )
}

@Composable
private fun DownloadSourceText(text: String, modifier: Modifier = Modifier) {
    MiuixText(
        text = text,
        modifier = modifier,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
}

@Composable
private fun DownloadSourceLatencyResult.asText(): String = when (status) {
    DownloadSourceLatencyStatus.Pending -> stringResource(R.string.download_source_latency_pending)
    DownloadSourceLatencyStatus.Timeout -> stringResource(R.string.download_source_latency_timeout)
    DownloadSourceLatencyStatus.Error -> stringResource(R.string.download_source_latency_failed)
    DownloadSourceLatencyStatus.Ok -> stringResource(R.string.download_source_latency_value, latencyMs)
}
