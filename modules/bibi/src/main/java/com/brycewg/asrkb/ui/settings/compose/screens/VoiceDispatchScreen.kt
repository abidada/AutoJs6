/**
 * 语音分发规则管理页（一级页）：总开关 + 测试匹配卡片 + 规则列表（按优先级降序）。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.host.VoiceCommandDispatcher
import com.brycewg.asrkb.host.voice.VoiceDispatchRule
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsThemedText
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VoiceDispatchRoute(
    uiMode: BibiUiMode,
    onBack: () -> Unit
) {
    var editingRuleId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    if (editingRuleId == null) {
        VoiceDispatchScreen(uiMode = uiMode, onBack = onBack, onEditRule = { editingRuleId = it })
    } else {
        VoiceDispatchRuleEditScreen(uiMode = uiMode, ruleId = editingRuleId, onBack = { editingRuleId = null })
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

    var dispatchEnabled by remember(context) { mutableStateOf(prefs.voiceDispatchEnabled) }
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
            item("master") {
                SettingsPreference(
                    entry = SettingsEntry.Switch(
                        id = "voice_dispatch_enabled",
                        titleRes = R.string.label_voice_dispatch_master,
                        summaryRes = R.string.summary_voice_dispatch_master,
                        checked = dispatchEnabled,
                        onCheckedChange = { enabled ->
                            prefs.voiceDispatchEnabled = enabled
                            dispatchEnabled = enabled
                        }
                    )
                )
            }

            item("test") {
                Column {
                    SettingsThemedText(
                        text = stringResource(R.string.label_voice_dispatch_test),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testInput,
                        onValueChange = { testInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.hint_voice_dispatch_test)) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            val hit = dispatcher.match(testInput)
                            testResult = if (hit == null) {
                                context.getString(R.string.voice_dispatch_test_miss)
                            } else {
                                val (rule, _) = hit
                                context.getString(
                                    R.string.voice_dispatch_test_hit,
                                    rule.priority,
                                    rule.name,
                                    rule.dispatchType.name,
                                    rule.payload
                                )
                            }
                        }) {
                            Text(stringResource(R.string.btn_voice_dispatch_test))
                        }
                        Spacer(Modifier.padding(start = 12.dp))
                        SettingsThemedText(
                            text = testResult,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item("rules_header") {
                SettingsThemedText(
                    text = stringResource(R.string.label_voice_dispatch_rules, rules.size),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            if (rules.isEmpty()) {
                item("rules_empty") {
                    SettingsThemedText(
                        text = stringResource(R.string.summary_voice_dispatch_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            items(rules.size, key = { rules[it].id }) { index ->
                val rule = rules[index]
                VoiceDispatchRuleCard(
                    rule = rule,
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

@Composable
private fun VoiceDispatchRuleCard(
    rule: VoiceDispatchRule,
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
        "$matchLabel \"$patternText\" → ${rule.dispatchType.name} ${rule.payload}"
    }
    val timeText = remember(rule.lastTriggeredAt) {
        if (rule.lastTriggeredAt <= 0L) {
            context.getString(R.string.voice_dispatch_never_triggered)
        } else {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            "${fmt.format(Date(rule.lastTriggeredAt))} · ${rule.triggerCount}"
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SettingsThemedText(
                    text = "${rule.name} · ${rule.priority}",
                    style = MaterialTheme.typography.titleSmall
                )
                SettingsThemedText(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall
                )
                SettingsThemedText(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
        Row {
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.btn_voice_dispatch_edit))
            }
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.btn_voice_dispatch_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
