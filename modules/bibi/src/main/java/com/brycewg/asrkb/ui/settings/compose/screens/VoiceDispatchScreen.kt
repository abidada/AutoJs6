/**
 * 语音分发规则管理页（一级页）：测试匹配卡片 + 规则列表（按优先级降序）。
 * 分发始终开启（无总开关）：每句识别结果统一匹配规则。
 * 规则项复用全局设置列表的标准行样式（分区卡片 + 56dp 最小行高 + 主题化开关），
 * 启用（选中）与停用（未选中）仅开关状态不同，行尺寸保持一致。
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.host.VoiceCommandDispatcher
import com.brycewg.asrkb.host.voice.VoiceDispatchRule
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMaterialItemSurface
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionTitle
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.components.SettingsThemedText
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.core.settingsSegmentedItemShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun VoiceDispatchRoute(
    uiMode: BibiUiMode,
    presetScriptPath: String?,
    onConsumePreset: () -> Unit,
    onBack: () -> Unit
) {
    // editing 与 ruleId 分开：ruleId == null 既表示「新建」，不能同时用来表示「不在编辑」
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var editingRuleId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    // 文件列表「设为语音规则」深链：打开新建规则页并预填脚本路径（一次性）
    var preset by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(presetScriptPath) {
        val path = presetScriptPath ?: return@LaunchedEffect
        preset = path
        editingRuleId = null
        editing = true
        onConsumePreset()
    }

    if (!editing) {
        VoiceDispatchScreen(
            uiMode = uiMode,
            onBack = onBack,
            onEditRule = {
                editingRuleId = it
                editing = true
            }
        )
    } else {
        VoiceDispatchRuleEditScreen(
            uiMode = uiMode,
            ruleId = editingRuleId,
            presetScriptPath = preset.takeIf { editingRuleId == null },
            onBack = {
                editing = false
                preset = null
            }
        )
    }
}

@Composable
internal fun VoiceDispatchScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onEditRule: (String?) -> Unit
) {
    val context = LocalContext.current
    val dispatcher = remember(context) { VoiceCommandDispatcher.getInstance(context) }
    val scope = rememberCoroutineScope()

    var rules by remember(context) { mutableStateOf<List<VoiceDispatchRule>>(emptyList()) }
    var testInput by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }

    suspend fun reloadRules() {
        rules = withContext(Dispatchers.IO) { dispatcher.getStore().load() }
            .sortedWith(
                compareByDescending<VoiceDispatchRule> { it.priority }.thenBy { it.createdAt }
            )
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { reloadRules() }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_voice_dispatch,
        onBack = onBack,
        actions = {
            TextButton(onClick = { onEditRule(null) }) {
                Text(stringResource(R.string.btn_voice_dispatch_new_rule))
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
            item("test") {
                Column {
                    SettingsSectionTitle(
                        text = stringResource(R.string.label_voice_dispatch_test),
                        uiMode = uiMode
                    )
                    SettingsSectionContainer(uiMode = uiMode) {
                        SettingsTextField(
                            uiMode = uiMode,
                            value = testInput,
                            onValueChange = { testInput = it },
                            label = stringResource(R.string.hint_voice_dispatch_test),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SettingsLayoutMetrics.ActionButtonRowHorizontalPadding)
                                .padding(
                                    top = SettingsLayoutMetrics.ActionButtonRowTopPadding,
                                    bottom = SettingsLayoutMetrics.ActionButtonRowBottomPadding
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsActionButton(
                                uiMode = uiMode,
                                text = stringResource(R.string.btn_voice_dispatch_test),
                                onClick = {
                                    val hit = dispatcher.match(testInput)
                                    testResult = if (hit == null) {
                                        context.getString(R.string.voice_dispatch_test_miss)
                                    } else {
                                        val (rule, _) = hit
                                        context.getString(
                                            R.string.voice_dispatch_test_hit,
                                            rule.priority,
                                            rule.name,
                                            dispatchTypeLabel(rule),
                                            rule.payload
                                        )
                                    }
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            SettingsThemedText(
                                text = testResult,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item("rules") {
                Column {
                    SettingsSectionTitle(
                        text = stringResource(R.string.label_voice_dispatch_rules, rules.size),
                        uiMode = uiMode
                    )
                    SettingsSectionContainer(uiMode = uiMode) {
                        if (rules.isEmpty()) {
                            AiBodyText(uiMode = uiMode, textRes = R.string.summary_voice_dispatch_empty)
                        } else {
                            rules.forEachIndexed { index, rule ->
                                VoiceDispatchRuleItem(
                                    uiMode = uiMode,
                                    rule = rule,
                                    index = index,
                                    count = rules.size,
                                    onToggle = { enabled ->
                                        scope.launch(Dispatchers.IO) {
                                            val store = dispatcher.getStore()
                                            val current = store.load()
                                            val idx = current.indexOfFirst { it.id == rule.id }
                                            if (idx >= 0) {
                                                val updated = current.toMutableList()
                                                updated[idx] = updated[idx].copy(enabled = enabled)
                                                store.save(updated)
                                                dispatcher.invalidateCache()
                                            }
                                            reloadRules()
                                        }
                                    },
                                    onEdit = { onEditRule(rule.id) },
                                    onDelete = {
                                        scope.launch(Dispatchers.IO) {
                                            val store = dispatcher.getStore()
                                            store.save(store.load().filterNot { it.id == rule.id })
                                            dispatcher.invalidateCache()
                                            reloadRules()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 分发类型展示名：执行脚本类型附加执行方式标识（LOOP/TIMED）。 */
private fun dispatchTypeLabel(rule: com.brycewg.asrkb.host.voice.VoiceDispatchRule): String =
    when {
        rule.dispatchType == com.brycewg.asrkb.host.voice.VoiceDispatchType.SCRIPT &&
            rule.scriptExecMode == com.brycewg.asrkb.host.voice.ScriptExecMode.LOOP ->
            "${rule.dispatchType.name}(LOOP)"

        rule.dispatchType == com.brycewg.asrkb.host.voice.VoiceDispatchType.SCRIPT &&
            rule.scriptExecMode == com.brycewg.asrkb.host.voice.ScriptExecMode.TIMED ->
            "${rule.dispatchType.name}(TIMED)"

        else -> rule.dispatchType.name
    }

@Composable
private fun VoiceDispatchRuleItem(
    uiMode: BibiUiMode,
    rule: VoiceDispatchRule,
    index: Int,
    count: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val summary = remember(rule) {
        val patternText = if (rule.matchType == com.brycewg.asrkb.host.voice.VoiceMatchType.REGEX) {
            rule.patterns.firstOrNull().orEmpty()
        } else {
            rule.patterns.joinToString(" / ") { "\"$it\"" }
        }
        val matchLabel = when (rule.matchType) {
            com.brycewg.asrkb.host.voice.VoiceMatchType.KEYWORD_INCLUDE ->
                context.getString(R.string.voice_dispatch_match_include)
            com.brycewg.asrkb.host.voice.VoiceMatchType.KEYWORD_EXACT ->
                context.getString(R.string.voice_dispatch_match_exact)
            com.brycewg.asrkb.host.voice.VoiceMatchType.REGEX ->
                context.getString(R.string.voice_dispatch_match_regex)
        }
        val typeLabel = dispatchTypeLabel(rule)
        "$matchLabel \"$patternText\" → $typeLabel ${rule.payload}"
    }
    val timeText = remember(rule.lastTriggeredAt) {
        if (rule.lastTriggeredAt <= 0L) {
            context.getString(R.string.voice_dispatch_never_triggered)
        } else {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            "${fmt.format(Date(rule.lastTriggeredAt))} · ${rule.triggerCount}"
        }
    }
    val title = "${rule.name} · ${rule.priority}"

    when (uiMode) {
        BibiUiMode.Miuix -> MiuixRuleItem(
            rule = rule,
            title = title,
            summary = summary,
            timeText = timeText,
            onToggle = onToggle,
            onEdit = onEdit,
            onDelete = onDelete
        )

        BibiUiMode.Material -> MaterialRuleItem(
            rule = rule,
            title = title,
            summary = summary,
            timeText = timeText,
            index = index,
            count = count,
            onToggle = onToggle,
            onEdit = onEdit,
            onDelete = onDelete
        )
    }
}

/** Miuix 风格规则行：与其他设置项同构（BasicComponent 标准行高/边距 + 主题化开关）。 */
@Composable
private fun MiuixRuleItem(
    rule: VoiceDispatchRule,
    title: String,
    summary: String,
    timeText: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    BasicComponent(
        title = title,
        summary = "$summary\n$timeText",
        onClick = {
            hapticTap()
            onToggle(!rule.enabled)
        },
        endActions = {
            MiuixIconButton(
                onClick = {
                    hapticTap()
                    onEdit()
                },
                modifier = Modifier.size(36.dp),
                minWidth = 36.dp,
                minHeight = 36.dp
            ) {
                MiuixIcon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.btn_voice_dispatch_edit),
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }
            MiuixIconButton(
                onClick = {
                    hapticTap()
                    onDelete()
                },
                modifier = Modifier.size(36.dp),
                minWidth = 36.dp,
                minHeight = 36.dp
            ) {
                MiuixIcon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.btn_voice_dispatch_delete),
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }
            MiuixSwitch(
                checked = rule.enabled,
                onCheckedChange = { checked ->
                    hapticTap()
                    onToggle(checked)
                }
            )
        }
    )
}

/** Material 风格规则行：分段表面 + 56dp 最小行高 + 标准开关，选中/未选中尺寸一致。 */
@Composable
private fun MaterialRuleItem(
    rule: VoiceDispatchRule,
    title: String,
    summary: String,
    timeText: String,
    index: Int,
    count: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = settingsSegmentedItemShape(index, count)
    SettingsMaterialItemSurface(
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(
                value = rule.enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = { checked ->
                    hapticTap()
                    onToggle(checked)
                }
            )
    ) {
        ListItem(
            modifier = Modifier.heightIn(min = SettingsLayoutMetrics.SettingsPreferenceMinHeight),
            headlineContent = {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
            },
            supportingContent = {
                Column {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            hapticTap()
                            onEdit()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.btn_voice_dispatch_edit),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            hapticTap()
                            onDelete()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.btn_voice_dispatch_delete),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = null,
                        interactionSource = interactionSource
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
