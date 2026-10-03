/**
 * 语音分发规则管理页（一级页）：测试匹配卡片 + 规则列表（按优先级降序）。
 * 分发始终开启（无总开关）：每句识别结果统一匹配规则。
 * 规则项为两排式卡片：第一排为整行宽度的文字描述（标签分组、默认单行截断、可展开/收起），
 * 第二排为编辑/删除按钮与启用开关；开关是唯一启停入口，文字区无点击交互。
 * 删除按钮先弹确认框（确认后才落盘删除）。
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.host.VoiceCommandDispatcher
import com.brycewg.asrkb.host.voice.VoiceDispatchRule
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMaterialItemSurface
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
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
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

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
    val prefs = remember(context) { Prefs(context) }
    val dispatcher = remember(context) { VoiceCommandDispatcher.getInstance(context) }
    val scope = rememberCoroutineScope()

    var rules by remember(context) { mutableStateOf<List<VoiceDispatchRule>>(emptyList()) }
    var testInput by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }
    var duplicatePolicy by remember { mutableStateOf(prefs.voiceDispatchDuplicatePolicy) }
    // 待删除规则：点删除先弹确认框，确认后才真正落盘
    var pendingDeleteRule by remember { mutableStateOf<VoiceDispatchRule?>(null) }

    // 测试按钮直连执行面：结果回显到测试文本区（不写规则统计）
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.brycewg.asrkb.host.voice.VoiceDispatchExecutor.onTestResult = { _, _, message ->
            val execText = message ?: context.getString(R.string.voice_dispatch_test_executed)
            testResult = testResult + "\n" +
                context.getString(R.string.voice_dispatch_test_exec_result, execText)
        }
        onDispose {
            com.brycewg.asrkb.host.voice.VoiceDispatchExecutor.onTestResult = null
        }
    }

    suspend fun reloadRules() {
        rules = withContext(Dispatchers.IO) { dispatcher.getStore().load() }
            .sortedWith(
                compareByDescending<VoiceDispatchRule> { it.priority }.thenBy { it.createdAt }
            )
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { reloadRules() }

    fun deleteRule(target: VoiceDispatchRule) {
        scope.launch(Dispatchers.IO) {
            val store = dispatcher.getStore()
            store.save(store.load().filterNot { it.id == target.id })
            dispatcher.invalidateCache()
            reloadRules()
        }
    }

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
            item("policy") {
                Column {
                    SettingsSectionTitle(
                        text = stringResource(R.string.label_voice_duplicate_policy),
                        uiMode = uiMode
                    )
                    SettingsSectionContainer(uiMode = uiMode) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SettingsThemedText(
                                text = stringResource(
                                    if (duplicatePolicy == Prefs.VoiceDuplicatePolicy.REFUSE) {
                                        R.string.summary_voice_duplicate_policy_refuse
                                    } else {
                                        R.string.summary_voice_duplicate_policy_forward
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    Prefs.VoiceDuplicatePolicy.REFUSE to R.string.voice_duplicate_policy_refuse,
                                    Prefs.VoiceDuplicatePolicy.FORWARD to R.string.voice_duplicate_policy_forward
                                ).forEach { (policy, labelRes) ->
                                    FilterChip(
                                        selected = duplicatePolicy == policy,
                                        onClick = {
                                            duplicatePolicy = policy
                                            prefs.voiceDispatchDuplicatePolicy = policy
                                        },
                                        label = { Text(stringResource(labelRes)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

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
                                    // 命中且为执行脚本类型：直连执行面真实执行一次
                                    // （与语音命中同链路；不计次、不写统计，结果异步追加到文本区）
                                    val hitRule = hit?.first
                                    if (hitRule != null &&
                                        hitRule.dispatchType ==
                                        com.brycewg.asrkb.host.voice.VoiceDispatchType.SCRIPT
                                    ) {
                                        com.brycewg.asrkb.host.voice.VoiceDispatchExecutor
                                            .executeForTest(context, hitRule)
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
                                VoiceDispatchRuleCard(
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
                                    onDelete = { pendingDeleteRule = rule }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val deleteTarget = pendingDeleteRule
    SettingsMessageDialog(
        state = if (deleteTarget == null) {
            null
        } else {
            SettingsMessageDialogState(
                title = stringResource(R.string.voice_dispatch_delete_confirm_title),
                message = stringResource(R.string.voice_dispatch_delete_confirm_message, deleteTarget.name),
                confirmText = stringResource(R.string.btn_voice_dispatch_delete),
                dismissText = stringResource(android.R.string.cancel),
                onConfirm = { deleteRule(deleteTarget) }
            )
        },
        uiMode = uiMode,
        onDismiss = { pendingDeleteRule = null }
    )
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

/**
 * 规则卡片：两排式布局。
 * 第一排为文字描述（任务/优先级标题行 + 匹配/类型/目标/参数/上次明细行），
 * 长内容默认单行省略，溢出时在右下角出现「展开/收起」开关（文字本身无点击交互）；
 * 第二排为编辑/删除按钮（左）与启用开关（右），中间以细分隔线隔开。
 * 启用状态下文字全亮，停用时整段文字与按钮降透明度（开关保持原样以示状态）。
 */
@Composable
private fun VoiceDispatchRuleCard(
    uiMode: BibiUiMode,
    rule: VoiceDispatchRule,
    index: Int,
    count: Int,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val matchValue = remember(rule) {
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
        "$matchLabel $patternText"
    }
    val lastValue = remember(rule.lastTriggeredAt, rule.triggerCount) {
        if (rule.lastTriggeredAt <= 0L) {
            context.getString(R.string.voice_dispatch_never_triggered)
        } else {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            context.getString(
                R.string.voice_dispatch_triggered_info,
                fmt.format(Date(rule.lastTriggeredAt)),
                rule.triggerCount
            )
        }
    }

    val hapticTap = LocalSettingsHapticTap.current
    var expanded by remember(rule.id) { mutableStateOf(false) }
    // 溢出检测闩锁：任一明细行截断即置位，展开后不回退，避免收起按钮闪烁
    var canExpand by remember(rule.id) { mutableStateOf(false) }
    val contentAlpha = if (rule.enabled) 1f else 0.5f
    val expandedMaxLines = if (expanded) Int.MAX_VALUE else 1

    val cardBody: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.voice_dispatch_field_task),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 2.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.voice_dispatch_field_priority),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = rule.priority.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
                RuleDetailRow(
                    label = stringResource(R.string.voice_dispatch_field_match),
                    value = matchValue,
                    valueMaxLines = expandedMaxLines,
                    onValueOverflow = { canExpand = true }
                )
                RuleDetailRow(
                    label = stringResource(R.string.voice_dispatch_field_type),
                    value = dispatchTypeLabel(rule)
                )
                RuleDetailRow(
                    label = stringResource(R.string.voice_dispatch_field_target),
                    value = rule.payload,
                    valueMaxLines = expandedMaxLines,
                    onValueOverflow = { canExpand = true }
                )
                rule.argsTemplate?.takeIf { it.isNotBlank() }?.let { args ->
                    RuleDetailRow(
                        label = stringResource(R.string.voice_dispatch_field_args),
                        value = args,
                        valueMaxLines = expandedMaxLines,
                        onValueOverflow = { canExpand = true }
                    )
                }
                RuleDetailRow(
                    label = stringResource(R.string.voice_dispatch_field_last),
                    value = lastValue
                )
                if (canExpand || expanded) {
                    Text(
                        text = stringResource(
                            if (expanded) R.string.voice_dispatch_collapse else R.string.voice_dispatch_expand
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                hapticTap()
                                expanded = !expanded
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        hapticTap()
                        onEdit()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .alpha(contentAlpha)
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
                    modifier = Modifier
                        .size(40.dp)
                        .alpha(contentAlpha)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.btn_voice_dispatch_delete),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.weight(1f))
                when (uiMode) {
                    BibiUiMode.Material -> Switch(
                        checked = rule.enabled,
                        onCheckedChange = { checked ->
                            hapticTap()
                            onToggle(checked)
                        }
                    )

                    BibiUiMode.Miuix -> MiuixSwitch(
                        checked = rule.enabled,
                        onCheckedChange = { checked ->
                            hapticTap()
                            onToggle(checked)
                        }
                    )
                }
            }
        }
    }

    when (uiMode) {
        BibiUiMode.Material -> SettingsMaterialItemSurface(
            shape = settingsSegmentedItemShape(index, count)
        ) {
            cardBody()
        }

        // Miuix 模式所有规则共处一张分区卡片，条目间以细分隔线区分
        BibiUiMode.Miuix -> Column {
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            cardBody()
        }
    }
}

/** 规则卡明细行：固定宽灰标签 + 值文本；值可限单行省略，截断时回调（驱动展开开关显隐）。 */
@Composable
private fun RuleDetailRow(
    label: String,
    value: String,
    valueMaxLines: Int = 1,
    onValueOverflow: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            onTextLayout = { layoutResult ->
                if (layoutResult.hasVisualOverflow) onValueOverflow?.invoke()
            }
        )
    }
}
