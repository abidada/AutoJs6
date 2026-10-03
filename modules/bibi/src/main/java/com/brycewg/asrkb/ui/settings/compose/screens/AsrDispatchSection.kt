/**
 * Compose ASR 设置页的语音分发区块：分发识别继续 + 分发反馈展示时长。
 * 重复命中策略已移至「语音分发」规则管理页顶部。
 * 自包含状态（直读写 Prefs），不经过 AsrSettingsViewModel。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.KEY_DISPATCH_CONTINUE_MODE
import com.brycewg.asrkb.store.KEY_DISPATCH_FEEDBACK_HOLD_SECONDS
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import kotlin.math.roundToInt

@Composable
internal fun AsrDispatchSection(
    uiMode: BibiUiMode,
    prefs: Prefs
) {
    var continueMode by remember { mutableStateOf(prefs.dispatchContinueMode) }
    var feedbackSeconds by remember { mutableIntStateOf(prefs.dispatchFeedbackHoldSeconds) }
    val secUnit = stringResource(R.string.time_unit_second)

    // 跨入口实时同步：长按悬浮球菜单等入口修改配置时，本页显示同步刷新
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_DISPATCH_CONTINUE_MODE -> continueMode = prefs.dispatchContinueMode
                KEY_DISPATCH_FEEDBACK_HOLD_SECONDS ->
                    feedbackSeconds = prefs.dispatchFeedbackHoldSeconds
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    AsrSection(uiMode = uiMode, titleRes = R.string.section_voice_dispatch) {
        AsrDropdownPreference(
            id = "dispatch_continue_mode",
            titleRes = R.string.label_dispatch_continue,
            options = listOf(
                DropdownOption(
                    Prefs.DispatchContinueMode.NONE.id,
                    stringResource(R.string.label_dispatch_continue_none)
                ),
                DropdownOption(
                    Prefs.DispatchContinueMode.ALWAYS.id,
                    stringResource(R.string.label_dispatch_continue_always)
                ),
                DropdownOption(
                    Prefs.DispatchContinueMode.ON_MISS.id,
                    stringResource(R.string.label_dispatch_continue_on_miss)
                )
            ),
            selectedOptionId = continueMode.id,
            index = 0,
            count = 2,
            onSelectedOptionChange = { id ->
                val mode = Prefs.DispatchContinueMode.fromId(id)
                continueMode = mode
                prefs.dispatchContinueMode = mode
            }
        )
        AsrSliderPreference(
            titleRes = R.string.label_dispatch_feedback_hold,
            valueLabel = { value -> "${value.roundToInt()} $secUnit" },
            value = feedbackSeconds.toFloat(),
            valueRange = Prefs.DISPATCH_FEEDBACK_HOLD_MIN_SECONDS.toFloat()..
                Prefs.DISPATCH_FEEDBACK_HOLD_MAX_SECONDS.toFloat(),
            steps = Prefs.DISPATCH_FEEDBACK_HOLD_MAX_SECONDS -
                Prefs.DISPATCH_FEEDBACK_HOLD_MIN_SECONDS - 1,
            uiMode = uiMode,
            highlightId = "dispatch_feedback_hold",
            index = 1,
            count = 2,
            onValueChange = { value ->
                val seconds = value.roundToInt().coerceIn(
                    Prefs.DISPATCH_FEEDBACK_HOLD_MIN_SECONDS,
                    Prefs.DISPATCH_FEEDBACK_HOLD_MAX_SECONDS
                )
                feedbackSeconds = seconds
                prefs.dispatchFeedbackHoldSeconds = seconds
            }
        )
    }
}
