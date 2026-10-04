/**
 * Compose 设置页滑块组件。
 * 拖动过程只更新本地显示，松手后才把最终值交给调用方，避免中间步进反复保存。
 * Miuix 无障碍 setProgress 没有 finished 回调，无指针按下时视为一次完整提交。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 滑块数值点击精确输入的规格。
 *
 * @param title 对话框标题
 * @param initialValueText 预填文本（单位与输入单位一致）
 * @param unitLabel 输入框单位 label
 * @param supportingText 取值范围说明
 * @param min 快捷 +/- 按钮的下限（与输入单位一致）
 * @param max 快捷 +/- 按钮的上限（与输入单位一致）
 * @param onConfirm 校验并提交，返回错误文案或 null
 */
internal class SliderEditDialogSpec(
    val title: String,
    val initialValueText: String,
    val unitLabel: String,
    val supportingText: String?,
    val min: Int,
    val max: Int,
    val onConfirm: (String) -> String?
)

@Composable
internal fun SettingsSliderPreference(
    title: String,
    valueLabel: (Float) -> String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    showKeyPoints: Boolean = steps in 1..10,
    startLabel: String? = null,
    endLabel: String? = null,
    highlightId: String? = null,
    index: Int = 0,
    count: Int = 1,
    editDialog: SliderEditDialogSpec? = null,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit = { _ -> }
) {
    val hapticTap = LocalSettingsHapticTap.current
    var sliderValue by remember { mutableFloatStateOf(value) }
    var isEditing by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val miuixPointerPressed = remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    LaunchedEffect(value) {
        if (!isEditing) {
            sliderValue = value
        }
    }

    val finishWithHaptic = {
        val committed = sliderValue
        isEditing = false
        if (committed != latestValue) {
            latestOnValueChange(committed)
        }
        latestOnValueChangeFinished(committed)
        hapticTap()
    }
    val sliderBottomPadding = if (index == count - 1) {
        SettingsLayoutMetrics.SliderLastItemBottomPadding
    } else {
        SettingsLayoutMetrics.SliderBottomPadding
    }
    val displayLabel = valueLabel(sliderValue)
    val sliderA11yModifier = Modifier.semantics { stateDescription = displayLabel }
    val content: @Composable () -> Unit = {
        SettingsControlLabel(
            title = title,
            value = displayLabel,
            onValueClick = if (editDialog != null) {{ showEditDialog = true }} else null
        )
        MiuixSlider(
            value = sliderValue,
            onValueChange = { next ->
                // Miuix 0.9.1 无障碍 setProgress 只回调 onValueChange，不回调
                // onValueChangeFinished。手指拖动时指针已按下，仍只预览；
                // 无指针则视为无障碍调整，立即提交并清掉编辑状态。
                if (miuixPointerPressed.value) {
                    isEditing = true
                    sliderValue = next
                } else {
                    sliderValue = next
                    finishWithHaptic()
                }
            },
            onValueChangeFinished = finishWithHaptic,
            valueRange = valueRange,
            steps = steps,
            showKeyPoints = showKeyPoints,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsLayoutMetrics.SliderHorizontalPadding)
                .padding(bottom = sliderBottomPadding)
                .pointerInput(Unit) {
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                miuixPointerPressed.value = event.changes.any { it.pressed }
                            }
                        }
                    } finally {
                        miuixPointerPressed.value = false
                    }
                }
                .then(sliderA11yModifier)
        )
        SettingsSliderScaleLabels(startLabel, endLabel)
    }
    if (highlightId == null) {
        content()
    } else {
        SettingsHighlightContainer(entryId = highlightId, content = content)
    }
    val spec = editDialog
    if (spec != null && showEditDialog) {
        SettingsNumberInputDialog(
            title = spec.title,
            initialValueText = spec.initialValueText,
            unitLabel = spec.unitLabel,
            supportingText = spec.supportingText,
            min = spec.min,
            max = spec.max,
            onConfirm = spec.onConfirm,
            onDismiss = { showEditDialog = false }
        )
    }
}

@Composable
private fun SettingsSliderScaleLabels(
    startLabel: String?,
    endLabel: String?
) {
    if (startLabel == null && endLabel == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsLayoutMetrics.SliderHorizontalPadding,
                vertical = SettingsLayoutMetrics.ControlLabelVerticalPadding
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MiuixText(
            text = startLabel.orEmpty(),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        MiuixText(
            text = endLabel.orEmpty(),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
    }
}

@Composable
internal fun SettingsControlLabel(
    title: String,
    value: String,
    onValueClick: (() -> Unit)? = null
) {
    // 可编辑时数值变为可点击入口；否则保持原有语义清空，避免与滑块重复朗读
    val valueModifier = if (onValueClick != null) {
        Modifier.clickable { onValueClick() }
    } else {
        Modifier.clearAndSetSemantics {
            // 数值由 Slider 的 stateDescription 朗读，避免重复播报
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsLayoutMetrics.ControlLabelHorizontalPadding,
                vertical = SettingsLayoutMetrics.ControlLabelVerticalPadding
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 标题占剩余宽度并自行换行，数值先按内容占位，保证右侧数值始终完整单行
        MiuixText(
            text = title,
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            style = MiuixTheme.textStyles.headline1,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.size(SettingsLayoutMetrics.ControlLabelSpacing))
        MiuixText(
            text = value,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            modifier = valueModifier
        )
    }
}
