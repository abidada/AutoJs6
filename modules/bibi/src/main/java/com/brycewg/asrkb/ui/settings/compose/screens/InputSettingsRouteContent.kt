/**
 * Compose 输入设置页路由内容编排。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.settingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry

internal typealias InputExplainedSwitchHandler = (
    current: Boolean,
    target: Boolean,
    titleRes: Int,
    offDescRes: Int,
    onDescRes: Int,
    preferenceKey: String,
    preCheck: ((Boolean) -> Boolean)?,
    onChanged: ((Boolean) -> Unit)?,
    write: (Boolean) -> Unit
) -> Unit

internal fun inputExplainedSwitchDialogState(
    context: android.content.Context,
    current: Boolean,
    target: Boolean,
    titleRes: Int,
    offDescRes: Int,
    onDescRes: Int,
    preferenceKey: String,
    preCheck: ((Boolean) -> Boolean)?,
    onChanged: ((Boolean) -> Unit)?,
    write: (Boolean) -> Unit,
    onRefreshState: () -> Unit
): SettingsFeatureExplainerDialogState? = settingsFeatureExplainerDialogState(
    context = context,
    titleRes = titleRes,
    offDescRes = offDescRes,
    onDescRes = onDescRes,
    currentState = current,
    preferenceKey = preferenceKey,
    onConfirm = {
        if (preCheck != null && !preCheck(target)) {
            onRefreshState()
            return@settingsFeatureExplainerDialogState
        }
        write(target)
        onChanged?.invoke(target)
        onRefreshState()
    }
)

@Composable
internal fun InputSettingsRouteContent(
    uiMode: BibiUiMode,
    innerPadding: PaddingValues,
    scrollModifier: Modifier,
    prefs: Prefs,
    uiState: InputSettingsUiState,
    actions: SettingsActionController,
    onUiStateChange: (InputSettingsUiState) -> Unit,
    onPendingHeadsetPermissionChange: (Boolean) -> Unit,
    onRequestBluetoothConnectPermission: () -> Unit,
    onRefreshState: () -> Unit,
    onShowExternalAidlGuideDialog: () -> Unit,
    onApplyExplainedSwitch: InputExplainedSwitchHandler
) {
    val context = LocalContext.current
    val behaviorItemCount = if (uiState.trimTrailingPunct) 5 else 4
    val behaviorTrimThresholdOffset = if (uiState.trimTrailingPunct) 1 else 0

    SettingsLazyColumn(
        uiMode = uiMode,
        modifier = Modifier.fillMaxSize(),
        miuixScrollModifier = scrollModifier,
        contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
        verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
    ) {
        item("behavior") {
            InputSection(uiMode = uiMode, titleRes = R.string.section_input_behavior) {
                InputExplainedSwitch(
                    id = "trim_trailing_punct",
                    titleRes = R.string.label_trim_trailing_punct,
                    checked = uiState.trimTrailingPunct,
                    onToggle = { target ->
                        onApplyExplainedSwitch(
                            uiState.trimTrailingPunct,
                            target,
                            R.string.label_trim_trailing_punct,
                            R.string.feature_trim_trailing_punct_off_desc,
                            R.string.feature_trim_trailing_punct_on_desc,
                            "trim_trailing_punct_explained",
                            null,
                            null
                        ) { prefs.trimFinalTrailingPunct = it }
                    },
                    index = 0,
                    count = behaviorItemCount
                )
                if (uiState.trimTrailingPunct) {
                    val unlimitedThresholdLabel = stringResource(
                        R.string.trim_trailing_punct_threshold_unlimited
                    )
                    InputSliderPreference(
                        titleRes = R.string.label_trim_trailing_punct_threshold,
                        valueLabel = { value ->
                            val next = value.roundToStep(step = 1).toInt().coerceIn(
                                Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_MIN,
                                Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_UNLIMITED
                            )
                            if (next == Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_UNLIMITED) {
                                unlimitedThresholdLabel
                            } else {
                                context.getString(
                                    R.string.trim_trailing_punct_threshold_value,
                                    next
                                )
                            }
                        },
                        value = uiState.trimTrailingPunctThreshold.toFloat(),
                        valueRange = Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_MIN.toFloat()..Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_UNLIMITED.toFloat(),
                        steps = Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_UNLIMITED -
                            Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_MIN - 1,
                        uiMode = uiMode,
                        showKeyPoints = false,
                        index = 1,
                        count = behaviorItemCount,
                        onValueChange = { value ->
                            val next = value.roundToStep(step = 1).toInt().coerceIn(
                                Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_MIN,
                                Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_UNLIMITED
                            )
                            onUiStateChange(uiState.copy(trimTrailingPunctThreshold = next))
                        },
                        onValueChangeFinished = { value ->
                            val next = value.roundToStep(step = 1).toInt().coerceIn(
                                Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_MIN,
                                Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_UNLIMITED
                            )
                            prefs.trimFinalTrailingPunctThreshold = next
                            onRefreshState()
                        }
                    )
                }
                InputExplainedSwitch(
                    id = "continuous_capture",
                    titleRes = R.string.label_continuous_capture,
                    checked = uiState.continuousCapture,
                    onToggle = { target ->
                        onApplyExplainedSwitch(
                            uiState.continuousCapture,
                            target,
                            R.string.label_continuous_capture,
                            R.string.feature_continuous_capture_off_desc,
                            R.string.feature_continuous_capture_on_desc,
                            "continuous_capture_explained",
                            null,
                            null
                        ) { prefs.continuousCaptureEnabled = it }
                    },
                    index = 1 + behaviorTrimThresholdOffset,
                    count = behaviorItemCount
                )
                InputExplainedSwitch(
                    id = "keep_screen_on_while_recording",
                    titleRes = R.string.label_keep_screen_on_while_recording,
                    checked = uiState.keepScreenOnWhileRecording,
                    onToggle = { target ->
                        onApplyExplainedSwitch(
                            uiState.keepScreenOnWhileRecording,
                            target,
                            R.string.label_keep_screen_on_while_recording,
                            R.string.feature_keep_screen_on_while_recording_off_desc,
                            R.string.feature_keep_screen_on_while_recording_on_desc,
                            "keep_screen_on_while_recording_explained",
                            null,
                            null
                        ) { prefs.keepScreenOnWhileRecording = it }
                    },
                    index = 2 + behaviorTrimThresholdOffset,
                    count = behaviorItemCount
                )
            }
        }

        item("audio") {
            InputSection(uiMode = uiMode, titleRes = R.string.section_audio_and_link) {
                InputExplainedSwitch(
                    id = "duck_media_on_record",
                    titleRes = R.string.label_audio_ducking_on_record,
                    checked = uiState.duckMediaOnRecord,
                    onToggle = { target ->
                        onApplyExplainedSwitch(
                            uiState.duckMediaOnRecord,
                            target,
                            R.string.label_audio_ducking_on_record,
                            R.string.feature_duck_media_on_record_off_desc,
                            R.string.feature_duck_media_on_record_on_desc,
                            "duck_media_on_record_explained",
                            null,
                            null
                        ) { prefs.duckMediaOnRecordEnabled = it }
                    },
                    index = 0,
                    count = 7
                )
                InputExplainedSwitch(
                    id = "offline_denoise",
                    titleRes = R.string.label_offline_denoise,
                    checked = uiState.offlineDenoise,
                    onToggle = { target ->
                        onApplyExplainedSwitch(
                            uiState.offlineDenoise,
                            target,
                            R.string.label_offline_denoise,
                            R.string.feature_offline_denoise_off_desc,
                            R.string.feature_offline_denoise_on_desc,
                            "offline_denoise_explained",
                            null,
                            null
                        ) { prefs.offlineDenoiseEnabled = it }
                    },
                    index = 1,
                    count = 7
                )
                InputExplainedSwitch(
                    id = "auto_cancel_empty_audio_input",
                    titleRes = R.string.label_auto_cancel_empty_audio_input,
                    checked = uiState.autoCancelEmptyAudioInput,
                    onToggle = { target ->
                        onApplyExplainedSwitch(
                            uiState.autoCancelEmptyAudioInput,
                            target,
                            R.string.label_auto_cancel_empty_audio_input,
                            R.string.feature_auto_cancel_empty_audio_input_off_desc,
                            R.string.feature_auto_cancel_empty_audio_input_on_desc,
                            "auto_cancel_empty_audio_input_explained",
                            null,
                            null
                        ) { prefs.autoCancelEmptyAudioInputEnabled = it }
                    },
                    index = 2,
                    count = 7
                )
                InputExplainedSwitch(
                    id = "auto_filter_silent_audio_segments",
                    titleRes = R.string.label_auto_filter_silent_audio_segments,
                    checked = uiState.autoFilterSilentAudioSegments,
                    onToggle = { target ->
                        onApplyExplainedSwitch(
                            uiState.autoFilterSilentAudioSegments,
                            target,
                            R.string.label_auto_filter_silent_audio_segments,
                            R.string.feature_auto_filter_silent_audio_segments_off_desc,
                            R.string.feature_auto_filter_silent_audio_segments_on_desc,
                            "auto_filter_silent_audio_segments_explained",
                            null,
                            null
                        ) { prefs.autoFilterSilentAudioSegmentsEnabled = it }
                    },
                    index = 3,
                    count = 7
                )
                InputExplainedSwitch(
                    id = "upload_audio_compression",
                    titleRes = R.string.label_upload_audio_compression,
                    checked = uiState.uploadAudioCompression,
                    onToggle = { target ->
                        onApplyExplainedSwitch(
                            uiState.uploadAudioCompression,
                            target,
                            R.string.label_upload_audio_compression,
                            R.string.feature_upload_audio_compression_off_desc,
                            R.string.feature_upload_audio_compression_on_desc,
                            "upload_audio_compression_explained",
                            null,
                            null
                        ) { prefs.uploadAudioCompressionEnabled = it }
                    },
                    index = 4,
                    count = 7
                )
                InputExplainedSwitch(
                    id = "headset_mic_priority",
                    titleRes = R.string.label_headset_mic_priority,
                    checked = uiState.headsetMicPriority,
                    onToggle = { target ->
                        onApplyExplainedSwitch(
                            uiState.headsetMicPriority,
                            target,
                            R.string.label_headset_mic_priority,
                            R.string.feature_headset_mic_priority_off_desc,
                            R.string.feature_headset_mic_priority_on_desc,
                            "headset_mic_priority_explained",
                            { enable ->
                                if (enable && needsBluetoothConnectPermission(context)) {
                                    onPendingHeadsetPermissionChange(true)
                                    onRequestBluetoothConnectPermission()
                                    false
                                } else {
                                    true
                                }
                            },
                            { enabled ->
                                if (!enabled) {
                                    BluetoothRouteManager.onRecordingStopped(context)
                                    BluetoothRouteManager.setImeActive(context, false)
                                }
                            }
                        ) { prefs.headsetMicPriorityEnabled = it }
                    },
                    index = 5,
                    count = 7
                )
                SettingsPreference(
                    entry = SettingsEntry.Switch(
                        id = "external_aidl",
                        titleRes = R.string.label_external_ime_link_aidl,
                        checked = uiState.externalAidl,
                        onCheckedChange = { enabled ->
                            prefs.externalAidlEnabled = enabled
                            onUiStateChange(uiState.copy(externalAidl = enabled))
                            if (enabled) onShowExternalAidlGuideDialog()
                        }
                    ),
                    index = 6,
                    count = 7
                )
            }
        }
    }
}

@Composable
internal fun InputHapticUiSettingsSection(
    uiMode: BibiUiMode,
    prefs: Prefs,
    uiState: InputSettingsUiState,
    onUiStateChange: (InputSettingsUiState) -> Unit,
    onRefreshState: () -> Unit
) {
    val context = LocalContext.current
    InputSection(uiMode = uiMode, titleRes = R.string.section_haptic_feedback) {
        InputSliderPreference(
            titleRes = R.string.label_haptic_feedback_strength,
            valueLabel = { value ->
                context.hapticFeedbackStrengthLabel(
                    value.toInt().coerceIn(
                        Prefs.HAPTIC_FEEDBACK_LEVEL_OFF,
                        Prefs.HAPTIC_FEEDBACK_LEVEL_HEAVY
                    )
                )
            },
            value = uiState.hapticFeedbackLevel.toFloat(),
            valueRange = Prefs.HAPTIC_FEEDBACK_LEVEL_OFF.toFloat()..Prefs.HAPTIC_FEEDBACK_LEVEL_HEAVY.toFloat(),
            steps = 5,
            uiMode = uiMode,
            highlightId = "haptic_feedback_strength",
            index = 0,
            count = 1,
            onValueChange = { value ->
                val level = value.toInt().coerceIn(
                    Prefs.HAPTIC_FEEDBACK_LEVEL_OFF,
                    Prefs.HAPTIC_FEEDBACK_LEVEL_HEAVY
                )
                onUiStateChange(uiState.withHapticFeedbackLevel(context, level))
            },
            onValueChangeFinished = { value ->
                val level = value.toInt().coerceIn(
                    Prefs.HAPTIC_FEEDBACK_LEVEL_OFF,
                    Prefs.HAPTIC_FEEDBACK_LEVEL_HEAVY
                )
                prefs.hapticFeedbackLevel = level
                onRefreshState()
            }
        )
    }
}

@Composable
internal fun InputMainUiSettingsSection(
    uiMode: BibiUiMode,
    prefs: Prefs,
    uiState: InputSettingsUiState,
    themeMode: String,
    onSetUiMode: (BibiUiMode) -> Unit,
    onSetThemeMode: (String) -> Unit,
    onRefreshState: () -> Unit,
    onApplyExplainedSwitch: InputExplainedSwitchHandler
) {
    val context = LocalContext.current
    val languageOptions = context.languageOptions().mapIndexed { index, label ->
        DropdownOption(languageTagForIndex(index), label)
    }
    val uiModeOptions = listOf(
        DropdownOption(BibiUiMode.Miuix.id, stringResource(R.string.settings_ui_mode_miuix)),
        DropdownOption(BibiUiMode.Material.id, stringResource(R.string.settings_ui_mode_material))
    )
    val themeModeOptions = listOf(
        DropdownOption("system", stringResource(R.string.settings_theme_mode_system)),
        DropdownOption("light", stringResource(R.string.settings_theme_mode_light)),
        DropdownOption("dark", stringResource(R.string.settings_theme_mode_dark))
    )
    InputSection(uiMode = uiMode, titleRes = R.string.section_main_ui) {
        SettingsPreference(
            entry = SettingsEntry.Dropdown(
                id = "language",
                titleRes = R.string.label_language,
                options = languageOptions,
                selectedOptionId = normalizeLanguageTag(prefs.appLanguageTag),
                onSelectedOptionChange = { tag ->
                    if (tag != prefs.appLanguageTag) {
                        prefs.appLanguageTag = tag
                        val locales = if (tag.isBlank()) {
                            LocaleListCompat.getEmptyLocaleList()
                        } else {
                            LocaleListCompat.forLanguageTags(tag)
                        }
                        AppCompatDelegate.setApplicationLocales(locales)
                        refreshFloatingNotificationLanguages(context, prefs)
                    }
                    onRefreshState()
                }
            ),
            index = 0,
            count = 5
        )
        SettingsPreference(
            entry = SettingsEntry.Dropdown(
                id = "settings_ui_mode",
                titleRes = R.string.settings_ui_mode,
                summaryRes = R.string.settings_ui_mode_summary,
                options = uiModeOptions,
                selectedOptionId = uiMode.id,
                onSelectedOptionChange = { onSetUiMode(BibiUiMode.fromId(it)) }
            ),
            index = 1,
            count = 5
        )
        SettingsPreference(
            entry = SettingsEntry.Dropdown(
                id = "settings_theme_mode",
                titleRes = R.string.settings_theme_mode,
                options = themeModeOptions,
                selectedOptionId = themeMode,
                onSelectedOptionChange = onSetThemeMode
            ),
            index = 2,
            count = 5
        )
        InputSliderPreference(
            titleRes = R.string.label_voice_ready_collapse,
            valueLabel = { value ->
                context.getString(R.string.voice_time_seconds_value, value.toInt())
            },
            value = prefs.voiceReadyCollapseSeconds.toFloat(),
            valueRange = 5f..60f,
            steps = 54,
            uiMode = uiMode,
            highlightId = "voice_ready_collapse",
            index = 3,
            count = 5,
            onValueChange = { },
            onValueChangeFinished = { value ->
                prefs.voiceReadyCollapseSeconds = value.toInt().coerceIn(5, 60)
                onRefreshState()
            }
        )
        InputExplainedSwitch(
            id = "hide_recent_task_card",
            titleRes = R.string.label_hide_recent_task_card,
            checked = uiState.hideRecentTasks,
            onToggle = { target ->
                onApplyExplainedSwitch(
                    uiState.hideRecentTasks,
                    target,
                    R.string.label_hide_recent_task_card,
                    R.string.feature_hide_recent_tasks_off_desc,
                    R.string.feature_hide_recent_tasks_on_desc,
                    "hide_recent_tasks_explained",
                    null,
                    { applyExcludeFromRecents(context, it) }
                ) { prefs.hideRecentTaskCard = it }
            },
            index = 4,
            count = 5
        )
    }
}

