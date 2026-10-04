/**
 * Compose 设置单选底部弹层。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:OptIn(ExperimentalLayoutApi::class)
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.annotation.ColorRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalBibiSettingsDark
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.core.settingsSegmentedItemShape
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class SettingsChoiceTag(
    val label: String,
    @param:ColorRes val bgColorResId: Int,
    @param:ColorRes val textColorResId: Int
)

internal data class SettingsChoiceItem(
    val title: String,
    val originalIndex: Int,
    val tags: List<SettingsChoiceTag> = emptyList(),
    val filterKeys: Set<String> = emptySet()
)

/** 筛选条一级选项;key 为 null 表示“全部”(不过滤)。 */
internal data class ChoiceSheetFilterLevelOption(
    val key: String?,
    val label: String,
    @param:ColorRes val bgColorResId: Int,
    @param:ColorRes val textColorResId: Int
)

/** 筛选条二级标签;同 dimension 的标签在行内归为一组展示。 */
internal data class ChoiceSheetFilterTagOption(
    val key: String,
    val label: String,
    val dimension: Int,
    @param:ColorRes val bgColorResId: Int,
    @param:ColorRes val textColorResId: Int
)

/** 选择弹层头部筛选条;仅由需要筛选的调用方(如 ASR 服务商选择器)传入。 */
internal data class ChoiceSheetFilterSpec(
    val levels: List<ChoiceSheetFilterLevelOption>,
    val tags: List<ChoiceSheetFilterTagOption>,
    val emptyText: String
)

internal data class SettingsChoiceGroup(
    val label: String,
    val items: List<SettingsChoiceItem>
)

internal data class SettingsChoiceSheetState(
    val title: String,
    val groups: List<SettingsChoiceGroup>,
    val selectedIndex: Int,
    val onSelected: (Int) -> Unit,
    val filter: ChoiceSheetFilterSpec? = null
)

/** Reusable state holder for provider -> model and other cascading choice sheets. */
@Stable
internal class SettingsChoiceSheetNavigator {
    var current by mutableStateOf<SettingsChoiceSheetState?>(null)
        private set
    private var next: SettingsChoiceSheetState? = null

    fun show(state: SettingsChoiceSheetState?) {
        next = null
        current = state
    }

    fun showAfterDismiss(state: SettingsChoiceSheetState?) {
        next = state
    }

    fun finishAfterDismiss() {
        next = null
    }

    fun onDismiss() {
        current = next
        next = null
    }
}

@Composable
internal fun rememberSettingsChoiceSheetNavigator(): SettingsChoiceSheetNavigator = remember { SettingsChoiceSheetNavigator() }

internal fun settingsChoiceSheetState(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
): SettingsChoiceSheetState? {
    if (items.isEmpty()) return null
    return SettingsChoiceSheetState(
        title = title,
        groups = listOf(
            SettingsChoiceGroup(
                label = "",
                items = items.mapIndexed { index, label ->
                    SettingsChoiceItem(
                        title = label,
                        originalIndex = index
                    )
                }
            )
        ),
        selectedIndex = selectedIndex.takeIf { it in items.indices } ?: -1,
        onSelected = onSelected
    )
}

@Composable
internal fun SettingsChoiceSheet(
    state: SettingsChoiceSheetState?,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit
) {
    val visibleState = state?.takeIf { sheetState ->
        sheetState.groups.any { it.items.isNotEmpty() }
    } ?: return
    when (uiMode) {
        BibiUiMode.Material -> MaterialChoiceSheet(
            state = visibleState,
            onDismiss = onDismiss
        )

        BibiUiMode.Miuix -> MiuixChoiceSheet(
            state = visibleState,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun MaterialChoiceSheet(
    state: SettingsChoiceSheetState?,
    onDismiss: () -> Unit
) {
    if (state == null) return
    key(state.title, state.groups, state.selectedIndex) {
        MaterialSettingsSheetScaffold(
            title = state.title,
            onDismiss = onDismiss,
            bottomPadding = 0.dp
        ) { dismissSheet ->
            ChoiceSheetList(
                state = state,
                uiMode = BibiUiMode.Material,
                onDismiss = dismissSheet
            )
        }
    }
}

@Composable
private fun MiuixChoiceSheet(
    state: SettingsChoiceSheetState?,
    onDismiss: () -> Unit
) {
    if (state == null) return
    var show by remember(state) { mutableStateOf(true) }
    var afterDismiss by remember(state) { mutableStateOf<(() -> Unit)?>(null) }
    // insideMargin 是上下对称的；为避免底部空白挡住列表，这里改为 0，
    // 标题顶部留白改由下方自绘标题承担。
    OverlayDialog(
        show = show,
        onDismissRequest = { show = false },
        onDismissFinished = {
            afterDismiss?.invoke()
            onDismiss()
        },
        insideMargin = DpSize(0.dp, 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MiuixText(
                text = state.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = SettingsLayoutMetrics.SheetDialogTitleTopPadding,
                        bottom = SettingsLayoutMetrics.SheetTitleBottomPadding
                    ),
                color = MiuixTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                style = MiuixTheme.textStyles.title4
            )
            ChoiceSheetList(
                state = state,
                uiMode = BibiUiMode.Miuix,
                onDismiss = { action ->
                    afterDismiss = action
                    show = false
                }
            )
        }
    }
}

@Composable
private fun ChoiceSheetList(
    state: SettingsChoiceSheetState,
    uiMode: BibiUiMode,
    onDismiss: (afterDismiss: () -> Unit) -> Unit
) {
    var selectionHandled by remember(state) { mutableStateOf(false) }
    val hapticTap = LocalSettingsHapticTap.current
    val filter = state.filter
    // 筛选状态随 state 实例重建而重置(每次打开弹层均为新实例)
    var filterLevelKey by remember(state) { mutableStateOf<String?>(null) }
    var filterTagKey by remember(state) { mutableStateOf<String?>(null) }
    val allItems = remember(state) { state.groups.flatMap { it.items } }

    fun matchesLevel(item: SettingsChoiceItem, levelKey: String?): Boolean =
        levelKey == null || item.filterKeys.contains(levelKey)

    fun matchesTag(item: SettingsChoiceItem, tagKey: String?): Boolean =
        tagKey == null || item.filterKeys.contains(tagKey)

    val visibleGroups = state.groups.map { group ->
        if (filter == null) {
            group
        } else {
            group.copy(
                items = group.items.filter {
                    matchesLevel(it, filterLevelKey) && matchesTag(it, filterTagKey)
                }
            )
        }
    }.filter { it.items.isNotEmpty() }
    if (visibleGroups.isEmpty() && filter == null) return
    val filterEmptyText = filter?.emptyText.orEmpty()
    Column(modifier = Modifier.fillMaxWidth()) {
        // 筛选条固定在标题下,不随列表滚动
        if (filter != null) {
            ChoiceSheetFilterBar(
                spec = filter,
                items = allItems,
                levelKey = filterLevelKey,
                tagKey = filterTagKey,
                onLevelSelect = { key ->
                    hapticTap()
                    filterLevelKey = key
                    // 一级切换后,原二级选中在新子集内无匹配时自动清空,避免空列表卡死
                    filterTagKey = filterTagKey?.takeIf { k ->
                        allItems.any { matchesLevel(it, key) && matchesTag(it, k) }
                    }
                },
                onTagSelect = { key ->
                    hapticTap()
                    filterTagKey = if (filterTagKey == key) null else key
                }
            )
        }
        SettingsSheetLazyColumn(
            uiMode = uiMode,
            contentPadding = PaddingValues(0.dp)
        ) {
            if (visibleGroups.isEmpty()) {
                item("choice-sheet-empty") {
                    ChoiceSheetEmptyText(text = filterEmptyText, uiMode = uiMode)
                }
            }
            visibleGroups.forEach { group ->
                if (group.label.isNotBlank()) {
                    item("header-${group.label}") {
                        ChoiceGroupHeader(label = group.label, uiMode = uiMode)
                    }
                }
                itemsIndexed(
                    items = group.items,
                    key = { _, item -> item.originalIndex }
                ) { index, item ->
                    ChoiceItemRow(
                        item = item,
                        selected = item.originalIndex == state.selectedIndex,
                        uiMode = uiMode,
                        index = index,
                        count = group.items.size,
                        onClick = {
                            if (selectionHandled) return@ChoiceItemRow
                            selectionHandled = true
                            hapticTap()
                            onDismiss { state.onSelected(item.originalIndex) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceGroupHeader(label: String, uiMode: BibiUiMode) {
    when (uiMode) {
        BibiUiMode.Material -> MaterialText(
            text = label,
            modifier = Modifier.padding(
                horizontal = SettingsLayoutMetrics.SheetGroupHeaderHorizontalPadding,
                vertical = SettingsLayoutMetrics.SheetGroupHeaderVerticalPadding
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge
        )

        BibiUiMode.Miuix -> MiuixText(
            text = label,
            modifier = Modifier.padding(
                horizontal = SettingsLayoutMetrics.SheetGroupHeaderHorizontalPadding,
                vertical = SettingsLayoutMetrics.SheetGroupHeaderVerticalPadding
            ),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1
        )
    }
}

@Composable
private fun ChoiceSheetFilterBar(
    spec: ChoiceSheetFilterSpec,
    items: List<SettingsChoiceItem>,
    levelKey: String?,
    tagKey: String?,
    onLevelSelect: (String?) -> Unit,
    onTagSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalBibiSettingsDark.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsLayoutMetrics.SheetGroupHeaderHorizontalPadding,
                vertical = SettingsLayoutMetrics.SheetGroupHeaderVerticalPadding
            ),
        verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.ChoiceTagSpacing)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.ChoiceTagSpacing)) {
            spec.levels.forEach { level ->
                // 一级计数反映“在当前二级选择下”的匹配数(标准分面计数)
                val count = items.count {
                    (level.key == null || it.filterKeys.contains(level.key)) &&
                        (tagKey == null || it.filterKeys.contains(tagKey))
                }
                ChoiceSheetFilterChip(
                    label = level.label,
                    count = count,
                    selected = level.key == levelKey,
                    enabled = true,
                    bgColorResId = level.bgColorResId,
                    textColorResId = level.textColorResId,
                    onClick = { onLevelSelect(level.key) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.ChoiceTagSpacing)
        ) {
            spec.tags.groupBy { it.dimension }.values.forEachIndexed { index, groupTags ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(width = 1.dp, height = 16.dp)
                            .background(
                                color = if (isDark) Color(0xFF3D3D3D) else Color(0xFFDCDCDC),
                                shape = RoundedCornerShape(1.dp)
                            )
                    )
                }
                groupTags.forEach { tag ->
                    // 二级计数反映“在当前一级子集下”的匹配数,为 0 时置灰禁用
                    val count = items.count {
                        (levelKey == null || it.filterKeys.contains(levelKey)) &&
                            it.filterKeys.contains(tag.key)
                    }
                    ChoiceSheetFilterChip(
                        label = tag.label,
                        count = null,
                        selected = tag.key == tagKey,
                        enabled = count > 0,
                        bgColorResId = tag.bgColorResId,
                        textColorResId = tag.textColorResId,
                        onClick = { onTagSelect(tag.key) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceSheetFilterChip(
    label: String,
    count: Int?,
    selected: Boolean,
    enabled: Boolean,
    @ColorRes bgColorResId: Int,
    @ColorRes textColorResId: Int,
    onClick: () -> Unit
) {
    val isDark = LocalBibiSettingsDark.current
    val selectedBg = asrTagColor(bgColorResId, isDark)
    val selectedFg = asrTagColor(textColorResId, isDark)
    val outlineColor = if (isDark) Color(0xFF4D4D4D) else Color(0xFFC4C4C4)
    val idleFg = if (isDark) Color(0xFFB4B4B4) else Color(0xFF6B6B6B)
    val contentAlpha = if (enabled) 1f else 0.38f
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) selectedBg else Color.Transparent,
        contentColor = (if (selected) selectedFg else idleFg).copy(alpha = contentAlpha),
        border = BorderStroke(
            width = 1.dp,
            color = (if (selected) selectedBg else outlineColor).copy(alpha = contentAlpha)
        ),
        modifier = Modifier
            .heightIn(min = 32.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = SettingsLayoutMetrics.ChoiceTagHorizontalPadding + 4.dp,
                vertical = SettingsLayoutMetrics.ChoiceTagVerticalPadding + 4.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            MaterialText(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
            if (count != null) {
                MaterialText(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun ChoiceSheetEmptyText(text: String, uiMode: BibiUiMode) {
    when (uiMode) {
        BibiUiMode.Material -> MaterialText(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsLayoutMetrics.SheetGroupHeaderHorizontalPadding, vertical = 40.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsLayoutMetrics.SheetGroupHeaderHorizontalPadding, vertical = 40.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            style = MiuixTheme.textStyles.footnote1
        )
    }
}

@Composable
private fun ChoiceItemRow(
    item: SettingsChoiceItem,
    selected: Boolean,
    uiMode: BibiUiMode,
    index: Int,
    count: Int,
    onClick: () -> Unit
) {
    when (uiMode) {
        BibiUiMode.Material -> Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SettingsLayoutMetrics.MaterialSectionItemSpacing)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick
                ),
            shape = settingsSegmentedItemShape(index, count),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(SettingsLayoutMetrics.MaterialSectionElevation),
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            ListItem(
                modifier = Modifier.heightIn(min = SettingsLayoutMetrics.SettingsPreferenceMinHeight),
                headlineContent = { MaterialText(item.title) },
                supportingContent = item.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                    { ChoiceTags(tags = tags) }
                },
                trailingContent = {
                    RadioButton(
                        selected = selected,
                        onClick = null
                    )
                },
                overlineContent = null,
                leadingContent = null,
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    supportingColor = MaterialTheme.colorScheme.outline
                ),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            )
        }

        BibiUiMode.Miuix -> RadioButtonPreference(
            title = item.title,
            selected = selected,
            onClick = onClick,
            radioButtonLocation = RadioButtonLocation.End,
            bottomAction = item.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                { ChoiceTags(tags = tags) }
            }
        )
    }
}

@Composable
private fun ChoiceTags(
    tags: List<SettingsChoiceTag>,
    modifier: Modifier = Modifier
) {
    val isDark = LocalBibiSettingsDark.current
    FlowRow(
        modifier = modifier.padding(top = SettingsLayoutMetrics.ChoiceTagTopPadding),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(SettingsLayoutMetrics.ChoiceTagSpacing),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(SettingsLayoutMetrics.ChoiceTagSpacing)
    ) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = asrTagColor(tag.bgColorResId, isDark),
                contentColor = asrTagColor(tag.textColorResId, isDark)
            ) {
                MaterialText(
                    text = tag.label,
                    modifier = Modifier.padding(
                        horizontal = SettingsLayoutMetrics.ChoiceTagHorizontalPadding,
                        vertical = SettingsLayoutMetrics.ChoiceTagVerticalPadding
                    ),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun asrTagColor(@ColorRes colorResId: Int, isDark: Boolean): Color = when (colorResId) {
    R.color.asr_tag_bg_online -> if (isDark) Color(0xFF1E2B3A) else Color(0xFFE3EBF4)
    R.color.asr_tag_fg_online -> if (isDark) Color(0xFFBBD4F4) else Color(0xFF2F4A67)
    R.color.asr_tag_bg_local -> if (isDark) Color(0xFF1D3328) else Color(0xFFE2EEE7)
    R.color.asr_tag_fg_local -> if (isDark) Color(0xFFBFE6D2) else Color(0xFF2E5A45)
    R.color.asr_tag_bg_recommended -> if (isDark) Color(0xFF382419) else Color(0xFFF8EBE2)
    R.color.asr_tag_fg_recommended -> if (isDark) Color(0xFFFFCCA6) else Color(0xFF844C27)
    R.color.asr_tag_bg_streaming -> if (isDark) Color(0xFF3A321E) else Color(0xFFF2EBD9)
    R.color.asr_tag_fg_streaming -> if (isDark) Color(0xFFFFE7B6) else Color(0xFF6B5630)
    R.color.asr_tag_bg_non_streaming -> if (isDark) Color(0xFF263233) else Color(0xFFE6EDEC)
    R.color.asr_tag_fg_non_streaming -> if (isDark) Color(0xFFCBE0E0) else Color(0xFF3E5B5C)
    R.color.asr_tag_bg_pseudo_streaming -> if (isDark) Color(0xFF322B1A) else Color(0xFFEFE2C8)
    R.color.asr_tag_fg_pseudo_streaming -> if (isDark) Color(0xFFFFE7B6) else Color(0xFF6B5630)
    R.color.asr_tag_bg_custom -> if (isDark) Color(0xFF2B2035) else Color(0xFFEAE4F1)
    R.color.asr_tag_fg_custom -> if (isDark) Color(0xFFE0C9F2) else Color(0xFF5B3E73)
    R.color.asr_tag_bg_cn_dialect -> if (isDark) Color(0xFF3A1F28) else Color(0xFFF0DEE4)
    R.color.asr_tag_fg_cn_dialect -> if (isDark) Color(0xFFFFB8C8) else Color(0xFF6A3D4A)
    R.color.asr_tag_bg_accurate -> if (isDark) Color(0xFF2A2F38) else Color(0xFFE5E8EF)
    R.color.asr_tag_fg_accurate -> if (isDark) Color(0xFFD8E0EF) else Color(0xFF3B4557)
    R.color.asr_tag_bg_last_used -> if (isDark) Color(0xFF2C2C2C) else Color(0xFFECECEC)
    R.color.asr_tag_fg_last_used -> if (isDark) Color(0xFFC9C9C9) else Color(0xFF4D4D4D)
    else -> Color.Unspecified
}
