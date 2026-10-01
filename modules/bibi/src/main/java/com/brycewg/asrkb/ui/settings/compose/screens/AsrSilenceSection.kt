/**
 * Compose ASR 设置页的静音自动停止区块。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SliderEditDialogSpec
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import kotlin.math.roundToInt

@Composable
internal fun AsrSilenceSection(
    uiMode: BibiUiMode,
    autoStopMode: Prefs.RecordingAutoStopMode,
    silenceWindowMs: Int,
    silenceSensitivity: Int,
    recordingMaxDurationMs: Int,
    onAutoStopModeChange: (Prefs.RecordingAutoStopMode) -> Unit,
    onWindowChange: (Int) -> Unit,
    onWindowFinished: () -> Unit,
    onSensitivityChange: (Int) -> Unit,
    onSensitivityFinished: () -> Unit,
    onMaxDurationChange: (Int) -> Unit,
    onMaxDurationFinished: () -> Unit
) {
    AsrSection(uiMode = uiMode, titleRes = R.string.section_silence_autostop) {
        val itemCount = when (autoStopMode) {
            Prefs.RecordingAutoStopMode.SILENCE -> 3
            Prefs.RecordingAutoStopMode.MAX_DURATION -> 2
            Prefs.RecordingAutoStopMode.MANUAL -> 1
        }
        AsrDropdownPreference(
            id = "recording_auto_stop_mode",
            titleRes = R.string.label_recording_auto_stop_mode,
            options = listOf(
                DropdownOption(
                    Prefs.RecordingAutoStopMode.MANUAL.id,
                    stringResource(R.string.option_recording_auto_stop_manual)
                ),
                DropdownOption(
                    Prefs.RecordingAutoStopMode.SILENCE.id,
                    stringResource(R.string.option_recording_auto_stop_silence)
                ),
                DropdownOption(
                    Prefs.RecordingAutoStopMode.MAX_DURATION.id,
                    stringResource(R.string.option_recording_auto_stop_max_duration)
                )
            ),
            selectedOptionId = autoStopMode.id,
            index = 0,
            count = itemCount,
            onSelectedOptionChange = { id ->
                onAutoStopModeChange(Prefs.RecordingAutoStopMode.fromId(id))
            }
        )
        if (autoStopMode == Prefs.RecordingAutoStopMode.SILENCE) {
            // 预先取好文案（stringResource 只能在组合期调用），供输入框校验回调复用
            val windowTitle = stringResource(R.string.label_silence_window_ms)
            val secUnit = stringResource(R.string.time_unit_second)
            // 判停阈值以“秒”为对外单位，内部仍按毫秒存储与钳位
            val minSec = Prefs.SILENCE_WINDOW_MIN_MS / 1000
            val maxSec = Prefs.TIME_RANGE_MAX_MS / 1000
            val windowRangeHint = stringResource(
                R.string.settings_input_range_hint,
                minSec,
                maxSec,
                secUnit
            )
            val errNotNumber = stringResource(R.string.settings_input_error_not_number)
            val errOutOfRange = stringResource(
                R.string.settings_input_error_out_of_range,
                minSec,
                maxSec,
                secUnit
            )
            AsrSliderPreference(
                titleRes = R.string.label_silence_window_ms,
                valueLabel = { (it.roundToNearestHundred() / 1000).toString() },
                value = silenceWindowMs.toFloat(),
                valueRange = Prefs.SILENCE_WINDOW_MIN_MS.toFloat()..Prefs.TIME_RANGE_MAX_MS.toFloat(),
                steps = 46,
                uiMode = uiMode,
                highlightId = "silence_window_ms",
                index = 1,
                count = itemCount,
                editDialog = SliderEditDialogSpec(
                    title = windowTitle,
                    initialValueText = (silenceWindowMs / 1000).toString(),
                    unitLabel = secUnit,
                    supportingText = windowRangeHint,
                    // 以秒为单位录入，提交时换算为毫秒
                    onConfirm = { text ->
                        val sec = text.toIntOrNull()
                        when {
                            sec == null -> errNotNumber
                            sec < minSec || sec > maxSec -> errOutOfRange
                            else -> {
                                onWindowChange(sec * 1000)
                                null
                            }
                        }
                    }
                ),
                onValueChange = { value -> onWindowChange(value.roundToNearestHundred()) },
                onValueChangeFinished = { onWindowFinished() }
            )
            AsrSliderPreference(
                titleRes = R.string.label_silence_sensitivity,
                valueLabel = { it.roundToInt().toString() },
                value = silenceSensitivity.toFloat(),
                valueRange = 1f..10f,
                steps = 8,
                uiMode = uiMode,
                highlightId = "silence_sensitivity",
                index = 2,
                count = itemCount,
                onValueChange = { value -> onSensitivityChange(value.roundToInt()) },
                onValueChangeFinished = { onSensitivityFinished() }
            )
        }
        if (autoStopMode == Prefs.RecordingAutoStopMode.MAX_DURATION) {
            val context = LocalContext.current
            val durationRange = Prefs.RECORDING_MAX_DURATION_MIN_MS.toFloat()..Prefs.RECORDING_MAX_DURATION_MAX_MS.toFloat()
            // 输入框以“秒”为单位，换算为毫秒提交
            val minSec = Prefs.RECORDING_MAX_DURATION_MIN_MS / 1000
            val maxSec = Prefs.RECORDING_MAX_DURATION_MAX_MS / 1000
            val durTitle = stringResource(R.string.label_recording_max_duration)
            val secUnit = stringResource(R.string.time_unit_second)
            val durationRangeHint = stringResource(R.string.settings_input_range_hint, minSec, maxSec, secUnit)
            val errNotNumber = stringResource(R.string.settings_input_error_not_number)
            val errOutOfRangeSec = stringResource(R.string.settings_input_error_out_of_range, minSec, maxSec, secUnit)
            AsrSliderPreference(
                titleRes = R.string.label_recording_max_duration,
                valueLabel = { value -> formatDurationLabel(context, value.roundToNearestDurationStep()) },
                value = recordingMaxDurationMs.toFloat(),
                valueRange = durationRange,
                steps = maxDurationSliderSteps(),
                uiMode = uiMode,
                showKeyPoints = false,
                highlightId = "recording_max_duration",
                index = 1,
                count = itemCount,
                editDialog = SliderEditDialogSpec(
                    title = durTitle,
                    initialValueText = (recordingMaxDurationMs / 1000).toString(),
                    unitLabel = secUnit,
                    supportingText = durationRangeHint,
                    onConfirm = { text ->
                        val sec = text.toLongOrNull()
                        when {
                            sec == null -> errNotNumber
                            sec < minSec || sec > maxSec -> errOutOfRangeSec
                            else -> {
                                onMaxDurationChange((sec * 1000).toInt())
                                null
                            }
                        }
                    }
                ),
                onValueChange = { value -> onMaxDurationChange(value.roundToNearestDurationStep()) },
                onValueChangeFinished = { onMaxDurationFinished() }
            )
        }
    }
}

private fun Float.roundToNearestHundred(): Int =
    ((this / 100f).roundToInt() * 100).coerceIn(Prefs.SILENCE_WINDOW_MIN_MS, Prefs.TIME_RANGE_MAX_MS)

/**
 * 将毫秒时长格式化为可读的“X小时Y分Z秒”。
 * 仅拼接非零的高位单位；全为 0 时显示“0秒”。
 */
private fun formatDurationLabel(context: Context, ms: Int): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    val sb = StringBuilder()
    if (hours > 0) sb.append(hours).append(context.getString(R.string.time_unit_hour))
    if (minutes > 0) sb.append(minutes).append(context.getString(R.string.time_unit_minute))
    if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append(context.getString(R.string.time_unit_second))
    return sb.toString()
}

private fun Float.roundToNearestDurationStep(): Int {
    val step = Prefs.RECORDING_MAX_DURATION_STEP_MS
    val rounded = (this / step.toFloat()).roundToInt() * step
    return rounded.coerceIn(Prefs.RECORDING_MAX_DURATION_MIN_MS, Prefs.RECORDING_MAX_DURATION_MAX_MS)
}

private fun maxDurationSliderSteps(): Int {
    val intervals = (Prefs.RECORDING_MAX_DURATION_MAX_MS - Prefs.RECORDING_MAX_DURATION_MIN_MS) /
        Prefs.RECORDING_MAX_DURATION_STEP_MS
    return (intervals - 1).coerceAtLeast(0)
}
