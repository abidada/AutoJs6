/**
 * Compose 设置多选底部弹层。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference

internal data class SettingsMultiChoiceSheetState(
    val title: String,
    val items: List<String>,
    val checkedIndices: Set<Int>,
    val selectedOrder: List<Int> = checkedIndices.toList(),
    val confirmText: String,
    val cancelText: String,
    val requiredSelectionCount: Int? = null,
    val maxSelectionCount: Int? = null,
    val maxSelectionMessage: String? = null,
    val showSelectionOrder: Boolean = false,
    val onSelectionRejected: ((String) -> Unit)? = null,
    val onConfirm: (List<Int>) -> Boolean
)

@Composable
internal fun SettingsMultiChoiceSheet(
    state: SettingsMultiChoiceSheetState?,
    onDismiss: () -> Unit
) {
    val visibleState = state?.takeIf { it.items.isNotEmpty() } ?: return
    MiuixMultiChoiceSheet(
        state = visibleState,
        onDismiss = onDismiss
    )
}

@Composable
private fun MiuixMultiChoiceSheet(
    state: SettingsMultiChoiceSheetState,
    onDismiss: () -> Unit
) {
    var selectedOrder by remember(state) { mutableStateOf(state.normalizedSelectedOrder()) }
    var show by remember(state) { mutableStateOf(true) }
    val hapticTap = LocalSettingsHapticTap.current
    OverlayDialog(
        show = show,
        title = state.title,
        onDismissRequest = { show = false },
        onDismissFinished = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MultiChoiceList(
                items = state.items,
                selectedOrder = selectedOrder,
                showSelectionOrder = state.showSelectionOrder,
                onToggle = { index, checked ->
                    updateSelectedOrder(
                        state = state,
                        selectedOrder = selectedOrder,
                        index = index,
                        checked = checked
                    )?.let { nextOrder ->
                        hapticTap()
                        selectedOrder = nextOrder
                    }
                }
            )
            SettingsSheetActionRow(
                cancelText = state.cancelText,
                confirmText = state.confirmText,
                confirmEnabled = state.isConfirmEnabled(selectedOrder),
                onDismiss = { show = false },
                onConfirm = {
                    if (state.onConfirm(selectedOrder)) {
                        show = false
                    }
                }
            )
        }
    }
}

@Composable
private fun MultiChoiceList(
    items: List<String>,
    selectedOrder: List<Int>,
    showSelectionOrder: Boolean,
    onToggle: (Int, Boolean) -> Unit
) {
    SettingsSheetLazyColumn(
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        itemsIndexed(
            items = items,
            key = { index, item -> "$index:$item" }
        ) { index, item ->
            val orderIndex = selectedOrder.indexOf(index)
            val checked = orderIndex >= 0
            val title = if (showSelectionOrder && orderIndex >= 0) {
                "${orderIndex + 1}. $item"
            } else {
                item
            }
            CheckboxPreference(
                title = title,
                checked = checked,
                onCheckedChange = { isChecked -> onToggle(index, isChecked) },
                checkboxLocation = CheckboxLocation.End
            )
        }
    }
}

private fun SettingsMultiChoiceSheetState.normalizedSelectedOrder(): List<Int> {
    val validOrder = selectedOrder.filter { it in items.indices }.distinct()
    val missing = checkedIndices
        .filter { it in items.indices && !validOrder.contains(it) }
    return validOrder + missing
}

private fun SettingsMultiChoiceSheetState.isConfirmEnabled(selectedOrder: List<Int>): Boolean {
    val requiredCount = requiredSelectionCount ?: return true
    return selectedOrder.size == requiredCount
}

private fun updateSelectedOrder(
    state: SettingsMultiChoiceSheetState,
    selectedOrder: List<Int>,
    index: Int,
    checked: Boolean
): List<Int>? = if (checked) {
    if (selectedOrder.contains(index)) {
        selectedOrder
    } else {
        val maxCount = state.maxSelectionCount
        if (maxCount != null && selectedOrder.size >= maxCount) {
            state.maxSelectionMessage?.let { state.onSelectionRejected?.invoke(it) }
            null
        } else {
            selectedOrder + index
        }
    }
} else {
    selectedOrder - index
}
