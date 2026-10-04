/**
 * AI 后处理模型测试成功弹窗：分段耗时条 + 色彩图例。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.TimingBarInterval
import com.brycewg.asrkb.ui.settings.compose.components.TimingIntervalBar
import com.brycewg.asrkb.ui.settings.compose.components.TimingLegendRow
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class AiLlmTestResultDialogState(
    val responseMode: LlmPostProcessor.LlmResponseMode,
    val totalMs: Long,
    val connectionMs: Long,
    val responseHeadersMs: Long,
    val firstVisibleMs: Long,
    val outputMs: Long,
    val responseBodyMs: Long,
    val preview: String?,
    val connectionReused: Boolean = false,
    val fallbackUsed: Boolean = false
)

private data class LlmTimingSegment(
    val label: String,
    val durationMs: Long,
    val color: Color
)

@Composable
internal fun AiLlmTestResultDialog(
    state: AiLlmTestResultDialogState?,
    onDismiss: () -> Unit
) {
    val visibleState = state ?: return
    val exit = rememberSettingsDialogExitController(visibleState)
    val title = stringResource(R.string.llm_test_success_title)
    val confirmText = stringResource(R.string.btn_confirm)

    fun finishDismiss() {
        exit.finish()
    }

    fun dismiss() {
        exit.dismiss(onDismiss)
    }

    val confirmAction = SettingsDialogAction(
        text = confirmText,
        onClick = ::dismiss,
        primary = true
    )

    OverlayDialog(
        show = exit.show,
        title = title,
        onDismissRequest = ::dismiss,
        onDismissFinished = ::finishDismiss
    ) {
        AiLlmTestResultContent(
            state = visibleState,
            modifier = Modifier.padding(bottom = SettingsLayoutMetrics.DialogContentBottomPadding)
        )
        SettingsDialogActionRow(
            actions = listOf(confirmAction)
        )
    }
}

@Composable
private fun AiLlmTestResultContent(
    state: AiLlmTestResultDialogState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        if (state.responseMode == LlmPostProcessor.LlmResponseMode.SSE) {
            val segments = llmSseTimingSegments(state)
            val barDescription = stringResource(
                R.string.llm_test_timing_bar_description,
                state.responseHeadersMs.toInt(),
                state.firstVisibleMs.toInt(),
                state.outputMs.toInt()
            )
            TimingIntervalBar(
                totalElapsedMs = segments.sumOf { it.durationMs.coerceAtLeast(0L) },
                intervals = llmTimingIntervals(segments),
                contentDescription = barDescription,
                trackColor = llmTimingTrackColor()
            )
            Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerSectionSpacing))
            segments.forEach { segment ->
                TimingLegendRow(
                    label = segment.label,
                    value = stringResource(
                        R.string.llm_test_timing_ms,
                        segment.durationMs.coerceAtLeast(0L).toInt()
                    ),
                    color = segment.color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = SettingsLayoutMetrics.ProDialogTinySpacing)
                )
            }
        } else {
            if (!state.connectionReused) {
                LlmTimingMetricRow(
                    label = stringResource(R.string.llm_test_timing_connect),
                    durationMs = state.connectionMs,
                )
            }
            LlmTimingMetricRow(
                label = stringResource(R.string.llm_test_timing_response_headers),
                durationMs = state.responseHeadersMs,
            )
            LlmTimingMetricRow(
                label = stringResource(R.string.llm_test_timing_response_body),
                durationMs = state.responseBodyMs,
            )
        }
        Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerLabelSpacing))
        LlmTimingBodyText(
            text = stringResource(R.string.llm_test_timing_total, state.totalMs.toInt()),
            secondary = true,
            strong = true
        )
        Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerLabelSpacing))
        LlmTimingBodyText(
            text = if (state.connectionReused) {
                stringResource(R.string.llm_test_timing_reused)
            } else {
                stringResource(R.string.llm_test_timing_new, state.connectionMs.toInt())
            },
            secondary = true
        )
        if (state.fallbackUsed) {
            Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerLabelSpacing))
            LlmTimingBodyText(
                text = stringResource(R.string.llm_test_timing_fallback_used),
                secondary = true
            )
        }
        val preview = state.preview?.trim().orEmpty()
        if (preview.isNotEmpty()) {
            Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerSectionSpacing))
            LlmTimingBodyText(
                text = stringResource(R.string.llm_test_success_preview, preview),
                secondary = true
            )
        }
        Spacer(modifier = Modifier.height(SettingsLayoutMetrics.FeatureExplainerLabelSpacing))
        LlmTimingBodyText(
            text = stringResource(
                if (state.responseMode == LlmPostProcessor.LlmResponseMode.SSE) {
                    R.string.llm_test_streaming_supported
                } else {
                    R.string.llm_test_streaming_unsupported
                }
            ),
            secondary = true
        )
    }
}

@Composable
private fun llmSseTimingSegments(
    state: AiLlmTestResultDialogState
): List<LlmTimingSegment> {
    val colors = llmTimingColors()
    return listOf(
        LlmTimingSegment(
            label = stringResource(R.string.llm_test_timing_response_headers),
            durationMs = state.responseHeadersMs.coerceAtLeast(0L),
            color = colors.responseHeaders
        ),
        LlmTimingSegment(
            label = stringResource(R.string.llm_test_timing_first_token),
            durationMs = state.firstVisibleMs.coerceAtLeast(0L),
            color = colors.firstToken
        ),
        LlmTimingSegment(
            label = stringResource(R.string.llm_test_timing_output),
            durationMs = state.outputMs.coerceAtLeast(0L),
            color = colors.output
        )
    )
}

private fun llmTimingIntervals(segments: List<LlmTimingSegment>): List<TimingBarInterval> {
    var startOffsetMs = 0L
    return segments.map { segment ->
        val endOffsetMs = startOffsetMs + segment.durationMs.coerceAtLeast(0L)
        TimingBarInterval(
            startOffsetMs = startOffsetMs,
            endOffsetMs = endOffsetMs,
            color = segment.color
        ).also {
            startOffsetMs = endOffsetMs
        }
    }
}

@Composable
private fun LlmTimingMetricRow(
    label: String,
    durationMs: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SettingsLayoutMetrics.ProDialogTinySpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LlmTimingBodyText(
            text = label,
            modifier = Modifier.weight(1f)
        )
        LlmTimingBodyText(
            text = stringResource(
                R.string.llm_test_timing_ms,
                durationMs.coerceAtLeast(0L).toInt()
            ),
            secondary = true,
            monospace = true
        )
    }
}

@Composable
private fun LlmTimingBodyText(
    text: String,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    strong: Boolean = false,
    monospace: Boolean = false
) {
    val weight = if (strong) FontWeight.Medium else FontWeight.Normal
    val fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
    MiuixText(
        text = text,
        modifier = modifier,
        color = if (secondary) {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        } else {
            MiuixTheme.colorScheme.onSurface
        },
        style = MiuixTheme.textStyles.body2,
        fontWeight = weight,
        fontFamily = fontFamily
    )
}

private data class LlmTimingColors(
    val responseHeaders: Color,
    val firstToken: Color,
    val output: Color
)

@Composable
private fun llmTimingColors(): LlmTimingColors = LlmTimingColors(
    responseHeaders = colorResource(R.color.llm_timing_blue),
    firstToken = colorResource(R.color.llm_timing_yellow),
    output = colorResource(R.color.llm_timing_green)
)

@Composable
private fun llmTimingTrackColor(): Color = MiuixTheme.colorScheme.surfaceVariant
