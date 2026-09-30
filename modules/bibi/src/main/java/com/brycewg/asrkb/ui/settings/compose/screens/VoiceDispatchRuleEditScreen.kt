/**
 * 语音分发规则编辑页（二级页）：名称/优先级/触发方式/关键词/分发类型/执行目标/参数模板。
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
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
import com.brycewg.asrkb.host.voice.VoiceDispatchType
import com.brycewg.asrkb.host.voice.VoiceMatchType
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun VoiceDispatchRuleEditScreen(
    uiMode: BibiUiMode,
    ruleId: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val dispatcher = remember(context) { VoiceCommandDispatcher.getInstance(context) }
    val scope = rememberCoroutineScope()

    val existing = remember(ruleId) {
        ruleId?.let { id -> dispatcher.getStore().load().firstOrNull { it.id == id } }
    }

    var name by remember(ruleId) { mutableStateOf(existing?.name ?: "") }
    var priority by remember(ruleId) { mutableStateOf((existing?.priority ?: 0).toFloat()) }
    var matchType by remember(ruleId) { mutableStateOf(existing?.matchType ?: VoiceMatchType.KEYWORD_INCLUDE) }
    var patternsText by remember(ruleId) {
        mutableStateOf(existing?.patterns?.joinToString("\n") ?: "")
    }
    var dispatchType by remember(ruleId) { mutableStateOf(existing?.dispatchType ?: VoiceDispatchType.SCRIPT) }
    var payload by remember(ruleId) { mutableStateOf(existing?.payload ?: "") }
    var argsTemplate by remember(ruleId) { mutableStateOf(existing?.argsTemplate ?: "") }
    var enabled by remember(ruleId) { mutableStateOf(existing?.enabled ?: true) }

    val regexInvalid = matchType == VoiceMatchType.REGEX &&
        patternsText.trim().isNotEmpty() &&
        try {
            Regex(patternsText.trim()); false
        } catch (_: Throwable) {
            true
        }

    val canSave = name.isNotBlank() && payload.isNotBlank() &&
        (!regexInvalid) && patternsText.isNotBlank()

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = if (existing == null) R.string.title_voice_dispatch_new else R.string.title_voice_dispatch_edit,
        onBack = onBack,
        actions = {
            androidx.compose.material3.TextButton(
                enabled = canSave,
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val store = dispatcher.getStore()
                        val now = System.currentTimeMillis()
                        val patterns = if (matchType == VoiceMatchType.REGEX) {
                            listOf(patternsText.trim())
                        } else {
                            patternsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        }
                        val rule = (existing ?: VoiceDispatchRule(
                            id = VoiceDispatchRule.newId(),
                            name = "",
                            matchType = matchType,
                            patterns = patterns,
                            dispatchType = dispatchType,
                            payload = payload,
                            createdAt = now
                        )).copy(
                            name = name.trim(),
                            enabled = enabled,
                            priority = priority.toInt().coerceIn(0, 100),
                            matchType = matchType,
                            patterns = patterns,
                            dispatchType = dispatchType,
                            payload = payload.trim(),
                            argsTemplate = argsTemplate.trim().takeIf { it.isNotEmpty() }
                        )
                        val current = store.load()
                        val idx = current.indexOfFirst { it.id == rule.id }
                        val next = if (idx >= 0) {
                            current.toMutableList().also { it[idx] = rule }
                        } else {
                            current + rule
                        }
                        store.save(next)
                        dispatcher.invalidateCache()
                    }
                    onBack()
                }
            ) {
                Text(stringResource(R.string.btn_voice_dispatch_save))
            }
        }
    ) { innerPadding, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(SettingsLayoutMetrics.pageContentPadding(innerPadding)),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 规则名 + 启用开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.label_voice_dispatch_rule_name)) },
                    singleLine = true
                )
                Spacer(Modifier.padding(start = 10.dp))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            // 优先级滑杆
            Text(
                text = stringResource(R.string.label_voice_dispatch_priority, priority.toInt()),
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = priority,
                onValueChange = { priority = it },
                valueRange = 0f..100f,
                steps = 99
            )

            // 触发方式（单选胶囊）
            Text(
                text = stringResource(R.string.label_voice_dispatch_match_type),
                style = MaterialTheme.typography.titleSmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceMatchType.entries.forEach { type ->
                    FilterChip(
                        selected = matchType == type,
                        onClick = { matchType = type },
                        label = {
                            Text(
                                when (type) {
                                    VoiceMatchType.KEYWORD_INCLUDE ->
                                        stringResource(R.string.voice_dispatch_match_include)
                                    VoiceMatchType.KEYWORD_EXACT ->
                                        stringResource(R.string.voice_dispatch_match_exact)
                                    VoiceMatchType.REGEX ->
                                        stringResource(R.string.voice_dispatch_match_regex)
                                }
                            )
                        }
                    )
                }
            }

            // 关键词 / 正则输入
            OutlinedTextField(
                value = patternsText,
                onValueChange = { patternsText = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        if (matchType == VoiceMatchType.REGEX) {
                            stringResource(R.string.label_voice_dispatch_regex)
                        } else {
                            stringResource(R.string.label_voice_dispatch_patterns)
                        }
                    )
                },
                supportingText = {
                    if (regexInvalid) {
                        Text(
                            text = stringResource(R.string.voice_dispatch_regex_invalid),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (matchType != VoiceMatchType.REGEX) {
                        Text(stringResource(R.string.hint_voice_dispatch_patterns))
                    }
                },
                isError = regexInvalid,
                minLines = if (matchType == VoiceMatchType.REGEX) 1 else 3
            )

            // 分发类型（单选胶囊）
            Text(
                text = stringResource(R.string.label_voice_dispatch_type),
                style = MaterialTheme.typography.titleSmall
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceDispatchType.entries.chunked(3).forEach { rowTypes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTypes.forEach { type ->
                            FilterChip(
                                selected = dispatchType == type,
                                onClick = { dispatchType = type },
                                label = {
                                    Text(
                                        when (type) {
                                            VoiceDispatchType.SCRIPT ->
                                                stringResource(R.string.voice_dispatch_type_script)
                                            VoiceDispatchType.AUTOMATION ->
                                                stringResource(R.string.voice_dispatch_type_automation)
                                            VoiceDispatchType.NATIVE ->
                                                stringResource(R.string.voice_dispatch_type_native)
                                            VoiceDispatchType.ANDROID_API ->
                                                stringResource(R.string.voice_dispatch_type_android_api)
                                            VoiceDispatchType.SCHEDULE ->
                                                stringResource(R.string.voice_dispatch_type_schedule)
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 执行目标
            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.label_voice_dispatch_payload)) },
                supportingText = { Text(stringResource(R.string.hint_voice_dispatch_payload)) },
                singleLine = true
            )

            // 参数模板
            OutlinedTextField(
                value = argsTemplate,
                onValueChange = { argsTemplate = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.label_voice_dispatch_args)) },
                supportingText = { Text(stringResource(R.string.hint_voice_dispatch_args)) },
                singleLine = true
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}
