/**
 * 悬浮球面板设置页（数据源管理端）。
 * 「已启用项」= 面板当前显示内容与顺序：长按手柄拖动排序、点按钮移除（可逆、最少保留 1 项）；
 * 「可添加项」= 目录中未启用的项，点击即追加到末尾；默认全部启用时该区为空态文案。
 * 每次操作直接写 Prefs 数据源，悬浮球下次长按展开即按新数据渲染。
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floatingball.FloatingPanelItemId
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMaterialItemSurface
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionTitle
import com.brycewg.asrkb.ui.settings.compose.components.SettingsThemedText
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.core.settingsSegmentedItemShape
import kotlin.math.abs

@Composable
internal fun FloatingPanelSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val hapticTap = LocalSettingsHapticTap.current

    // 显式保存模型：enabledItems 为内存草稿，baseline 为数据源当前快照；仅「保存」落盘，离开未保存则丢弃
    var baseline by remember(context) { mutableStateOf(FloatingPanelItemId.loadOrder(prefs)) }
    var enabledItems by remember(context) { mutableStateOf(baseline) }
    var pendingExit by remember { mutableStateOf(false) }
    var exitChoice by remember { mutableStateOf(UnsavedExit.NONE) }
    val isDirty = enabledItems != baseline

    // 剪贴板两项仅当同步开关开启时可配置（与面板运行时行为一致）
    val clipboardSyncEnabled = remember(context) {
        try {
            prefs.syncClipboardEnabled
        } catch (_: Throwable) {
            false
        }
    }
    val availableItems = remember(enabledItems, clipboardSyncEnabled) {
        FloatingPanelItemId.entries.filter { candidate ->
            candidate !in enabledItems &&
                (
                    clipboardSyncEnabled ||
                        (
                            candidate != FloatingPanelItemId.ClipboardUpload &&
                                candidate != FloatingPanelItemId.ClipboardPull
                            )
                    )
        }
    }

    fun save() {
        FloatingPanelItemId.saveOrder(prefs, enabledItems)
        baseline = enabledItems
    }

    fun requestBack() {
        if (isDirty) {
            pendingExit = true
        } else {
            onBack()
        }
    }

    // 系统返回手势/返回键：有未保存修改时拦截弹确认框（页内 BackHandler 优先于根路由 PredictiveBackHandler）
    BackHandler(enabled = isDirty) { pendingExit = true }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_floating_panel_settings,
        onBack = { requestBack() },
        actions = {
            TextButton(
                enabled = isDirty,
                onClick = {
                    hapticTap()
                    save()
                }
            ) {
                Text(stringResource(R.string.btn_floating_panel_save))
            }
        }
    ) { innerPadding, scrollModifier ->
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("hint") {
                SettingsSectionContainer(uiMode = uiMode) {
                    SettingsThemedText(
                        text = stringResource(R.string.desc_floating_panel_settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            item("enabled") {
                Column {
                    SettingsSectionTitle(
                        text = stringResource(R.string.label_floating_panel_enabled),
                        uiMode = uiMode
                    )
                    SettingsSectionContainer(uiMode = uiMode) {
                        PanelItemsReorderList(
                            uiMode = uiMode,
                            items = enabledItems,
                            onMove = { from, to ->
                                enabledItems = enabledItems.toMutableList().apply {
                                    add(to, removeAt(from))
                                }
                            },
                            onDelete = { item ->
                                if (enabledItems.size > 1) {
                                    enabledItems = enabledItems - item
                                }
                            }
                        )
                    }
                }
            }

            item("available") {
                Column {
                    SettingsSectionTitle(
                        text = stringResource(R.string.label_floating_panel_available),
                        uiMode = uiMode
                    )
                    SettingsSectionContainer(uiMode = uiMode) {
                        if (availableItems.isEmpty()) {
                            SettingsThemedText(
                                text = stringResource(R.string.summary_floating_panel_all_added),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        } else {
                            availableItems.forEachIndexed { index, item ->
                                AddablePanelRow(
                                    uiMode = uiMode,
                                    item = item,
                                    index = index,
                                    count = availableItems.size,
                                    onAdd = {
                                        enabledItems = enabledItems + item
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 离开确认框：保存=落盘后返回；不保存=丢弃草稿返回；点对话框外=留在本页继续编辑
    SettingsMessageDialog(
        state = if (pendingExit) {
            SettingsMessageDialogState(
                title = stringResource(R.string.dialog_floating_panel_unsaved_title),
                message = stringResource(R.string.dialog_floating_panel_unsaved_message),
                confirmText = stringResource(R.string.btn_floating_panel_save),
                dismissText = stringResource(R.string.btn_floating_panel_discard),
                onConfirm = {
                    save()
                    exitChoice = UnsavedExit.SAVE
                },
                onDismissAction = {
                    exitChoice = UnsavedExit.DISCARD
                }
            )
        } else {
            null
        },
        uiMode = uiMode,
        onDismiss = {
            when (exitChoice) {
                UnsavedExit.SAVE -> onBack()
                UnsavedExit.DISCARD -> {
                    enabledItems = baseline
                    onBack()
                }

                UnsavedExit.NONE -> {}
            }
            exitChoice = UnsavedExit.NONE
            pendingExit = false
        }
    )
}

/** 离开页面对未保存草稿的处理选择（点对话框外关闭 = NONE，留在本页）。 */
private enum class UnsavedExit {
    NONE,
    SAVE,
    DISCARD
}

/** 拖拽排序状态：按 item.id 跟踪拖动行（列表重排时状态不丢），offset 为相对原位的纵向位移。 */
private class PanelDragState {
    var draggingId by mutableStateOf<FloatingPanelItemId?>(null)

    /** 拖动行在列表中的当前下标（拖动开始时定位，每次换位后同步更新）。 */
    var draggingIndex by mutableStateOf(-1)
    var dragOffsetY by mutableStateOf(0f)
    var rowHeightPx by mutableStateOf(0)
}

/** 已启用项列表：行间拼接卡片，长按手柄拖动换位（累计位移超过行高即与相邻项交换）。 */
@Composable
private fun PanelItemsReorderList(
    uiMode: BibiUiMode,
    items: List<FloatingPanelItemId>,
    onMove: (Int, Int) -> Unit,
    onDelete: (FloatingPanelItemId) -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val dragState = remember { PanelDragState() }
    // pointerInput 的 key 是 item.id（重排不重启手势），回调里需读到最新列表/回调，用 rememberUpdatedState 防旧闭包
    val currentItems by rememberUpdatedState(items)
    val currentOnMove by rememberUpdatedState(onMove)

    Column {
        items.forEach { item ->
            key(item.id) {
                val index = items.indexOf(item)
                val isDragging = dragState.draggingId == item
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .onSizeChanged { dragState.rowHeightPx = it.height }
                        .graphicsLayer {
                            translationY = if (isDragging) dragState.dragOffsetY else 0f
                            scaleX = if (isDragging) 1.02f else 1f
                            scaleY = if (isDragging) 1.02f else 1f
                            alpha = if (isDragging) 0.92f else 1f
                        }
                ) {
                    PanelItemRow(
                        uiMode = uiMode,
                        item = item,
                        index = index,
                        count = items.size,
                        deleteEnabled = items.size > 1,
                        dragHandleModifier = Modifier
                            .size(36.dp)
                            .pointerInput(item.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragState.draggingId = item
                                        dragState.draggingIndex = currentItems.indexOf(item)
                                        dragState.dragOffsetY = 0f
                                        hapticTap()
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (dragState.draggingId != item) return@detectDragGesturesAfterLongPress
                                        if (dragState.rowHeightPx <= 0) return@detectDragGesturesAfterLongPress
                                        dragState.dragOffsetY += dragAmount.y
                                        var from = dragState.draggingIndex
                                        while (from >= 0 && abs(dragState.dragOffsetY) >= dragState.rowHeightPx) {
                                            val direction = if (dragState.dragOffsetY > 0f) 1 else -1
                                            val target = from + direction
                                            if (target !in currentItems.indices) {
                                                dragState.dragOffsetY = 0f
                                                break
                                            }
                                            currentOnMove(from, target)
                                            dragState.dragOffsetY -= direction * dragState.rowHeightPx
                                            from = target
                                            dragState.draggingIndex = target
                                        }
                                    },
                                    onDragEnd = {
                                        dragState.draggingId = null
                                        dragState.draggingIndex = -1
                                        dragState.dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        dragState.draggingId = null
                                        dragState.draggingIndex = -1
                                        dragState.dragOffsetY = 0f
                                    }
                                )
                            },
                        onDelete = { onDelete(item) }
                    )
                }
            }
        }
    }
}

/** 已启用面板项行：图标 + 名称 + 移除按钮 + 拖动手柄；行本身无点击交互。 */
@Composable
private fun PanelItemRow(
    uiMode: BibiUiMode,
    item: FloatingPanelItemId,
    index: Int,
    count: Int,
    deleteEnabled: Boolean,
    dragHandleModifier: Modifier,
    onDelete: () -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(item.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            IconButton(
                onClick = {
                    hapticTap()
                    onDelete()
                },
                enabled = deleteEnabled,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.RemoveCircleOutline,
                    contentDescription = stringResource(R.string.btn_delete),
                    modifier = Modifier.size(20.dp),
                    tint = if (deleteEnabled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = stringResource(R.string.desc_floating_panel_drag),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier
            )
        }
    }
    when (uiMode) {
        BibiUiMode.Material -> SettingsMaterialItemSurface(
            shape = settingsSegmentedItemShape(index, count)
        ) {
            row()
        }

        // Miuix 模式所有行共处一张分区卡片，行间以细分隔线区分
        BibiUiMode.Miuix -> Column {
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            row()
        }
    }
}

/** 可添加项行：点击整行追加到「已启用项」末尾。 */
@Composable
private fun AddablePanelRow(
    uiMode: BibiUiMode,
    item: FloatingPanelItemId,
    index: Int,
    count: Int,
    onAdd: () -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    hapticTap()
                    onAdd()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(item.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.desc_floating_panel_add),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    when (uiMode) {
        BibiUiMode.Material -> SettingsMaterialItemSurface(
            shape = settingsSegmentedItemShape(index, count)
        ) {
            row()
        }

        BibiUiMode.Miuix -> Column {
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            row()
        }
    }
}
