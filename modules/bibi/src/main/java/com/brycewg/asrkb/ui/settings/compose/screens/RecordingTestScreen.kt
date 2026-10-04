/**
 * 设置页录音测试 Compose 页面。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrParallelEngineDecision
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import java.util.Locale
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RecordingTestScreen(
    onBack: () -> Unit,
    onOpenAsrSettings: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember(appContext) { Prefs(appContext) }
    val viewModel: RecordingTestViewModel = viewModel(
        factory = remember(appContext, prefs) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordingTestViewModel(appContext, prefs) as T
            }
        }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.refreshConfiguredAsr()
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.releasePageResources()
        }
    }

    SettingsDetailScaffold(
        titleRes = R.string.title_recording_test,
        onBack = onBack,
        bottomBar = {
            RecordingTestBottomBar(
                isRecording = state.isRecording,
                onClick = viewModel::toggleRecording
            )
        }
    ) { innerPadding, scrollModifier ->
        SettingsLazyColumn(
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("overview") {
                RecordingOverviewSection(state = state)
            }
            item("audio") {
                RecordingAudioSection(
                    state = state,
                    onPlay = viewModel::replay,
                    onReset = viewModel::resetRecording
                )
            }
            item("pipeline") {
                RecognitionPipelineSection(
                    state = state,
                    onPromptSelected = viewModel::selectPromptPreset,
                    onAiProcess = viewModel::processAi,
                    onOpenAsrSettings = onOpenAsrSettings
                )
            }
            item("results") {
                RecordingResultsSection(state = state)
            }
        }
    }
}

@Composable
private fun RecordingOverviewSection(
    state: RecordingTestUiState
) {
    RecordingSection(titleRes = R.string.recording_test_latency_chain) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RecordingStatusPill(state = state)
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    RecordingText(
                        text = stringResource(R.string.recording_test_total_label),
                        style = RecordingTextStyle.Label
                    )
                    RecordingText(
                        text = formatRecordingTestDuration(state.totalLatencyMs),
                        style = RecordingTextStyle.Display
                    )
                }
            }
            LatencySegments(state = state)
            RecordingMetricList {
                RecordingMetricRow(
                    label = stringResource(R.string.recording_test_press_time),
                    value = formatRecordingTestTimestamp(state.pressWallTimeMs)
                )
                RecordingMetricRow(
                    label = stringResource(R.string.recording_test_first_frame_time),
                    value = formatRecordingTestTimestamp(state.firstFrameWallTimeMs)
                )
            }
        }
    }
}

@Composable
private fun RecordingStatusPill(
    state: RecordingTestUiState
) {
    val (text, icon, color) = when {
        state.isRecording -> Triple(
            stringResource(R.string.recording_test_status_recording),
            Icons.Rounded.GraphicEq,
            RecordingAccentColor.Primary
        )
        state.isTranscribing || state.isAiProcessing -> Triple(
            stringResource(R.string.recording_test_status_processing),
            Icons.Rounded.AutoFixHigh,
            RecordingAccentColor.Tertiary
        )
        state.hasAudio -> Triple(
            stringResource(R.string.recording_test_status_ready),
            Icons.Rounded.PlayArrow,
            RecordingAccentColor.Secondary
        )
        else -> Triple(
            stringResource(R.string.recording_test_status_idle),
            Icons.Rounded.Mic,
            RecordingAccentColor.Neutral
        )
    }
    val containerColor = recordingAccentContainerColor(color)
    val contentColor = recordingAccentContentColor(color)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RecordingIcon(imageVector = icon, tint = contentColor, size = 18.dp)
        RecordingText(
            text = text,
            color = contentColor,
            style = RecordingTextStyle.LabelStrong
        )
    }
}

@Composable
private fun LatencySegments(
    state: RecordingTestUiState
) {
    val segments = listOf(
        RecordingLatencySegment(
            label = stringResource(R.string.recording_test_segment_record),
            value = state.recordLatencyMs,
            color = RecordingLatencyBandColor.Green
        ),
        RecordingLatencySegment(
            label = stringResource(R.string.recording_test_segment_asr),
            value = state.asrLatencyMs,
            color = RecordingLatencyBandColor.Blue
        ),
        RecordingLatencySegment(
            label = stringResource(R.string.recording_test_segment_ai),
            value = state.aiLatencyMs,
            color = RecordingLatencyBandColor.Orange
        )
    )
    val knownTotal = segments.mapNotNull { it.value }.sum().coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(14.dp))
    ) {
        segments.forEach { segment ->
            val value = segment.value
            val hasLatency = value != null
            val weight = ((value ?: (knownTotal / 3L).coerceAtLeast(1L)).toFloat() / knownTotal)
                .coerceAtLeast(0.24f)
            val segmentColor = recordingLatencySegmentColor(segment.color, hasLatency)
            val contentColor = recordingLatencySegmentContentColor(segment.color, hasLatency)
            Column(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .background(segmentColor)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RecordingText(
                    text = segment.label,
                    color = contentColor,
                    style = RecordingTextStyle.Label,
                    maxLines = 1
                )
                RecordingText(
                    text = formatRecordingTestDuration(value),
                    color = contentColor,
                    style = RecordingTextStyle.Caption,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun RecordingAudioSection(
    state: RecordingTestUiState,
    onPlay: () -> Unit,
    onReset: () -> Unit
) {
    RecordingSection(titleRes = R.string.recording_test_audio_title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AudioLevelMeter(state = state)
            RecordingMetricList {
                RecordingMetricRow(
                    label = stringResource(R.string.recording_test_duration_label),
                    value = String.format(Locale.US, "%.1fs", state.durationMs / 1_000.0)
                )
                RecordingMetricRow(
                    label = stringResource(R.string.recording_test_current_level_label),
                    value = formatDb(state.currentDb)
                )
                RecordingMetricRow(
                    label = stringResource(R.string.recording_test_peak_level_label),
                    value = formatDb(state.peakDb)
                )
                RecordingMetricRow(
                    label = stringResource(R.string.recording_test_silence_label),
                    value = state.silentPercent?.let { percent ->
                        stringResource(R.string.recording_test_percent_value, percent)
                    } ?: "--"
                )
            }
            SettingsActionButtonRow(padded = false) {
                SettingsActionButton(
                    text = if (state.isPlaying) {
                        stringResource(R.string.recording_test_playing)
                    } else {
                        stringResource(R.string.recording_test_play)
                    },
                    enabled = state.canPlay,
                    leadingIcon = Icons.Rounded.PlayArrow,
                    onClick = onPlay,
                    modifier = Modifier.weight(1f)
                )
                SettingsActionButton(
                    text = stringResource(R.string.recording_test_rerecord),
                    enabled = state.hasAudio && !state.isRecording,
                    leadingIcon = Icons.Rounded.Refresh,
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AudioLevelMeter(
    state: RecordingTestUiState
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RecordingMeterRow(
            label = stringResource(R.string.recording_test_current_level_label),
            value = formatDb(state.currentDb),
            progress = levelProgress(state.currentDb),
            color = RecordingAccentColor.Primary
        )
        RecordingMeterRow(
            label = stringResource(R.string.recording_test_peak_level_label),
            value = formatDb(state.peakDb),
            progress = levelProgress(state.peakDb),
            color = RecordingAccentColor.Secondary
        )
    }
}

@Composable
private fun RecordingMeterRow(
    label: String,
    value: String,
    progress: Float,
    color: RecordingAccentColor
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RecordingText(text = label, style = RecordingTextStyle.Caption)
            RecordingText(text = value, style = RecordingTextStyle.CaptionStrong)
        }
        RecordingProgressIndicator(
            progress = progress,
            color = color
        )
    }
}

@Composable
private fun RecognitionPipelineSection(
    state: RecordingTestUiState,
    onPromptSelected: (String) -> Unit,
    onAiProcess: () -> Unit,
    onOpenAsrSettings: () -> Unit
) {
    val context = LocalContext.current
    val promptOptions = remember(state.promptPresets) {
        state.promptPresets.map { preset ->
            DropdownOption(
                id = preset.id,
                label = preset.title.takeIf { it.isNotBlank() } ?: preset.id
            )
        }
    }
    val selectedPromptId = state.selectedPromptId.takeIf { id -> promptOptions.any { it.id == id } }
        ?: promptOptions.firstOrNull()?.id
        ?: ""
    val noPromptLabel = stringResource(R.string.recording_test_no_prompt)

    RecordingSection(titleRes = R.string.recording_test_recognition_title) {
        SettingsPreference(
            entry = SettingsEntry.Action(
                id = "recording_test_asr",
                titleRes = R.string.recording_test_asr_label,
                summary = configuredAsrSummary(context, state),
                icon = Icons.Rounded.Mic,
                enabled = !state.isRecording && !state.isTranscribing && !state.isAiProcessing,
                onClick = onOpenAsrSettings
            ),
            index = 0,
            count = 2
        )
        SettingsPreference(
            entry = SettingsEntry.Dropdown(
                id = "recording_test_prompt",
                titleRes = R.string.recording_test_ai_label,
                summary = stringResource(R.string.recording_test_prompt_summary),
                icon = Icons.Rounded.AutoFixHigh,
                enabled = promptOptions.isNotEmpty(),
                options = promptOptions.ifEmpty {
                    listOf(DropdownOption("", noPromptLabel))
                },
                selectedOptionId = selectedPromptId,
                onSelectedOptionChange = onPromptSelected
            ),
            index = 1,
            count = 2
        )
        SettingsActionButtonRow() {
            SettingsActionButton(
                text = stringResource(R.string.recording_test_auto_transcribe),
                enabled = false,
                leadingIcon = if (state.isTranscribing) null else Icons.Rounded.TextFields,
                leadingContent = if (state.isTranscribing) {
                    { RecordingBusyIndicator() }
                } else {
                    null
                },
                onClick = {},
                modifier = Modifier.weight(1f)
            )
            SettingsActionButton(
                text = stringResource(R.string.recording_test_ai_process),
                enabled = state.canAiProcess,
                leadingIcon = if (state.isAiProcessing) null else Icons.Rounded.AutoFixHigh,
                leadingContent = if (state.isAiProcessing) {
                    { RecordingBusyIndicator() }
                } else {
                    null
                },
                onClick = onAiProcess,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun configuredAsrSummary(
    context: android.content.Context,
    state: RecordingTestUiState
): String {
    val primary = AsrVendorUi.name(context, state.currentAsrVendor)
    val mode = when (state.asrMode) {
        RecordingTestAsrMode.PushPcm -> stringResource(R.string.recording_test_asr_mode_push_pcm)
        RecordingTestAsrMode.File -> stringResource(R.string.recording_test_asr_mode_file)
    }
    val backup = state.backupAsrVendor?.let { vendor ->
        val strategy = state.backupAsrStrategy?.let { backupAsrStrategyLabel(it) }.orEmpty()
        stringResource(R.string.recording_test_asr_backup_suffix, AsrVendorUi.name(context, vendor), strategy)
    } ?: ""
    return stringResource(R.string.recording_test_asr_summary, primary, mode, backup)
}

@Composable
private fun backupAsrStrategyLabel(strategy: AsrParallelEngineDecision): String = when (strategy) {
    AsrParallelEngineDecision.UseParallel -> stringResource(R.string.recording_test_backup_strategy_parallel)
    AsrParallelEngineDecision.UseLazyLocalBackup -> stringResource(R.string.recording_test_backup_strategy_lazy)
    AsrParallelEngineDecision.UsePrimaryOnly -> ""
}

@Composable
private fun RecordingResultsSection(
    state: RecordingTestUiState
) {
    RecordingSection(titleRes = R.string.recording_test_result_compare) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ResultBlock(
                label = stringResource(R.string.recording_test_raw_result),
                value = state.rawText.ifBlank { stringResource(R.string.recording_test_empty_result_placeholder) },
                icon = Icons.Rounded.TextFields,
                accent = RecordingAccentColor.Tertiary,
                empty = state.rawText.isBlank()
            )
            ResultBlock(
                label = stringResource(R.string.recording_test_ai_result),
                value = state.aiText.ifBlank { stringResource(R.string.recording_test_empty_result_placeholder) },
                icon = Icons.Rounded.AutoFixHigh,
                accent = RecordingAccentColor.Secondary,
                empty = state.aiText.isBlank()
            )
            state.statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
                RecordingStatusMessage(message = message)
            }
        }
    }
}

@Composable
private fun ResultBlock(
    label: String,
    value: String,
    icon: ImageVector,
    accent: RecordingAccentColor,
    empty: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(recordingNeutralContainerColor())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RecordingIcon(
                imageVector = icon,
                tint = recordingAccentSolidColor(accent),
                size = 18.dp
            )
            RecordingText(
                text = label,
                style = RecordingTextStyle.LabelStrong
            )
        }
        RecordingText(
            text = value,
            color = if (empty) recordingSecondaryTextColor() else recordingPrimaryTextColor(),
            style = RecordingTextStyle.Body,
            maxLines = 5
        )
    }
}

@Composable
private fun RecordingStatusMessage(
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(recordingErrorContainerColor())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        RecordingText(
            text = message,
            color = recordingErrorContentColor(),
            style = RecordingTextStyle.Body,
            maxLines = 3
        )
    }
}

@Composable
private fun RecordingMetricList(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun RecordingMetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RecordingText(
            text = label,
            color = recordingSecondaryTextColor(),
            style = RecordingTextStyle.Body,
            modifier = Modifier.weight(1f)
        )
        RecordingText(
            text = value,
            style = RecordingTextStyle.BodyStrong,
            maxLines = 1
        )
    }
}

@Composable
private fun RecordingSection(
    titleRes: Int,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSectionContainer(
        titleRes = titleRes
    ) {
        content()
    }
}

@Composable
private fun RecordingProgressIndicator(
    progress: Float,
    color: RecordingAccentColor
) {
    val modifier = Modifier
        .fillMaxWidth()
        .height(8.dp)
        .clip(RoundedCornerShape(4.dp))
    MiuixLinearProgressIndicator(
        progress = progress.coerceIn(0f, 1f),
        modifier = modifier,
        colors = ProgressIndicatorDefaults.progressIndicatorColors(
            foregroundColor = recordingAccentSolidColor(color)
        )
    )
}

@Composable
private fun RecordingTestBottomBar(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(
        topStart = SettingsLayoutMetrics.BottomBarTopCorner,
        topEnd = SettingsLayoutMetrics.BottomBarTopCorner
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .heightIn(min = SettingsLayoutMetrics.BottomBarMinHeight)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        RecordingTestRecordButton(
            isRecording = isRecording,
            onClick = onClick
        )
    }
}

@Composable
private fun RecordingTestRecordButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    SettingsActionButton(
        text = if (isRecording) {
            stringResource(R.string.recording_test_stop)
        } else {
            stringResource(R.string.recording_test_record)
        },
        leadingIcon = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RecordingBusyIndicator() {
    CircularProgressIndicator(
        modifier = Modifier.size(SettingsLayoutMetrics.ActionButtonIconSize),
        strokeWidth = 2.dp
    )
}

@Composable
private fun RecordingIcon(
    imageVector: ImageVector,
    tint: Color,
    size: androidx.compose.ui.unit.Dp
) {
    top.yukonga.miuix.kmp.basic.Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = tint
    )
}

@Composable
private fun RecordingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: RecordingTextStyle = RecordingTextStyle.Body,
    maxLines: Int = Int.MAX_VALUE
) {
    val resolvedColor = if (color == Color.Unspecified) {
        when (style) {
            RecordingTextStyle.Caption,
            RecordingTextStyle.Label -> recordingSecondaryTextColor()
            else -> recordingPrimaryTextColor()
        }
    } else {
        color
    }
    MiuixText(
        text = text,
        modifier = modifier,
        color = resolvedColor,
        style = when (style) {
            RecordingTextStyle.Display -> MiuixTheme.textStyles.title2
            RecordingTextStyle.Body,
            RecordingTextStyle.BodyStrong -> MiuixTheme.textStyles.body2
            RecordingTextStyle.Label,
            RecordingTextStyle.LabelStrong -> MiuixTheme.textStyles.body1
            RecordingTextStyle.Caption,
            RecordingTextStyle.CaptionStrong -> MiuixTheme.textStyles.footnote1
        },
        fontWeight = when (style) {
            RecordingTextStyle.Display,
            RecordingTextStyle.BodyStrong,
            RecordingTextStyle.LabelStrong,
            RecordingTextStyle.CaptionStrong -> FontWeight.SemiBold
            else -> FontWeight.Normal
        },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

private data class RecordingLatencySegment(
    val label: String,
    val value: Long?,
    val color: RecordingLatencyBandColor
)

private enum class RecordingTextStyle {
    Display,
    Body,
    BodyStrong,
    Label,
    LabelStrong,
    Caption,
    CaptionStrong
}

private enum class RecordingAccentColor {
    Primary,
    Secondary,
    Tertiary,
    Neutral
}

@Composable
private fun recordingLatencySegmentColor(
    bandColor: RecordingLatencyBandColor,
    completed: Boolean
): Color {
    val solidColor = when (bandColor) {
        RecordingLatencyBandColor.Green -> colorResource(R.color.recording_latency_green)
        RecordingLatencyBandColor.Blue -> colorResource(R.color.recording_latency_blue)
        RecordingLatencyBandColor.Orange -> colorResource(R.color.recording_latency_orange)
    }
    if (completed) return solidColor
    return solidColor.copy(alpha = 0.18f)
}

@Composable
private fun recordingLatencySegmentContentColor(
    bandColor: RecordingLatencyBandColor,
    completed: Boolean
): Color {
    if (!completed) return recordingSecondaryTextColor()
    return when (bandColor) {
        RecordingLatencyBandColor.Green -> colorResource(R.color.recording_latency_on_green)
        RecordingLatencyBandColor.Blue -> colorResource(R.color.recording_latency_on_blue)
        RecordingLatencyBandColor.Orange -> colorResource(R.color.recording_latency_on_orange)
    }
}

private enum class RecordingLatencyBandColor {
    Green,
    Blue,
    Orange
}

@Composable
private fun recordingAccentSolidColor(
    accent: RecordingAccentColor
): Color = when (accent) {
    RecordingAccentColor.Primary -> MiuixTheme.colorScheme.primary
    RecordingAccentColor.Secondary -> MiuixTheme.colorScheme.secondary
    RecordingAccentColor.Tertiary -> MiuixTheme.colorScheme.secondaryVariant
    RecordingAccentColor.Neutral -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
private fun recordingAccentContainerColor(
    accent: RecordingAccentColor
): Color = recordingAccentSolidColor(accent).copy(alpha = 0.14f)

@Composable
private fun recordingAccentContentColor(
    accent: RecordingAccentColor
): Color = recordingAccentSolidColor(accent)

@Composable
private fun recordingAccentOnSolidColor(
    accent: RecordingAccentColor
): Color = MiuixTheme.colorScheme.onSurface

@Composable
private fun recordingNeutralContainerColor(): Color = MiuixTheme.colorScheme.surfaceVariant

@Composable
private fun recordingPrimaryTextColor(): Color = MiuixTheme.colorScheme.onSurface

@Composable
private fun recordingSecondaryTextColor(): Color = MiuixTheme.colorScheme.onSurfaceVariantSummary

@Composable
private fun recordingErrorContainerColor(): Color = MiuixTheme.colorScheme.error.copy(alpha = 0.14f)

@Composable
private fun recordingErrorContentColor(): Color = MiuixTheme.colorScheme.error

private fun levelProgress(value: Float?): Float {
    if (value == null) return 0f
    return ((value + 60f) / 60f).coerceIn(0f, 1f)
}

private fun formatDb(value: Float?): String = value?.let {
    String.format(Locale.US, "%.0fdB", it)
} ?: "--dB"
