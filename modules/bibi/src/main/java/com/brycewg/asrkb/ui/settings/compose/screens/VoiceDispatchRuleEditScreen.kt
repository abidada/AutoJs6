/**
 * 语音分发规则编辑页（二级页）：名称/优先级/触发方式/关键词/分发类型/执行目标/参数模板。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.brycewg.asrkb.host.BibiLoopConfig
import com.brycewg.asrkb.host.ScriptHost
import com.brycewg.asrkb.host.VoiceCommandDispatcher
import com.brycewg.asrkb.host.voice.ScriptExecMode
import com.brycewg.asrkb.host.voice.TimedTaskSnapshot
import com.brycewg.asrkb.host.voice.VoiceDispatchRule
import com.brycewg.asrkb.host.voice.VoiceDispatchType
import com.brycewg.asrkb.host.voice.VoiceMatchType
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.ScriptPickerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsThemedText
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun VoiceDispatchRuleEditScreen(
    uiMode: BibiUiMode,
    ruleId: String?,
    presetScriptPath: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val dispatcher = remember(context) { VoiceCommandDispatcher.getInstance(context) }
    val scope = rememberCoroutineScope()

    val existing = remember(ruleId) {
        ruleId?.let { id -> dispatcher.getStore().load().firstOrNull { it.id == id } }
    }

    // 文件列表「设为语音规则」预填：仅新建（无存量规则）时生效
    val presetPath = if (existing == null) presetScriptPath?.trim()?.takeIf { it.isNotEmpty() } else null

    var name by remember(ruleId, presetPath) {
        mutableStateOf(existing?.name ?: presetPath?.let { java.io.File(it).nameWithoutExtension } ?: "")
    }
    var priority by remember(ruleId, presetPath) { mutableStateOf((existing?.priority ?: 0).toFloat()) }
    var cooldownSeconds by remember(ruleId, presetPath) {
        mutableStateOf(((existing?.cooldownMs ?: 0L) / 1000f).coerceIn(0f, 60f))
    }
    var matchType by remember(ruleId, presetPath) {
        mutableStateOf(existing?.matchType ?: VoiceMatchType.KEYWORD_INCLUDE)
    }
    var patternsText by remember(ruleId, presetPath) {
        mutableStateOf(existing?.patterns?.joinToString("\n") ?: "")
    }
    var dispatchType by remember(ruleId, presetPath) {
        mutableStateOf(existing?.dispatchType ?: VoiceDispatchType.SCRIPT)
    }
    var scriptExecMode by remember(ruleId, presetPath) {
        mutableStateOf(existing?.scriptExecMode ?: ScriptExecMode.IMMEDIATE)
    }
    var loopTimes by remember(ruleId, presetPath) { mutableStateOf(existing?.loopTimes ?: 1) }
    var loopDelayMs by remember(ruleId, presetPath) { mutableStateOf(existing?.loopDelayMs ?: 0L) }
    var loopIntervalMs by remember(ruleId, presetPath) { mutableStateOf(existing?.loopIntervalMs ?: 0L) }
    var loopConfigDirty by remember(ruleId, presetPath) { mutableStateOf(false) }
    var timedTaskId by remember(ruleId, presetPath) { mutableStateOf(existing?.timedTaskId) }
    var timedTaskSnapshot by remember(ruleId, presetPath) { mutableStateOf(existing?.timedTaskSnapshot) }
    var timedConfigDirty by remember(ruleId, presetPath) { mutableStateOf(false) }
    var payload by remember(ruleId, presetPath) {
        mutableStateOf(existing?.payload ?: presetPath ?: "")
    }
    var argsTemplate by remember(ruleId, presetPath) { mutableStateOf(existing?.argsTemplate ?: "") }
    var enabled by remember(ruleId, presetPath) { mutableStateOf(existing?.enabled ?: true) }
    var showScriptPicker by remember(ruleId, presetPath) { mutableStateOf(false) }

    val regexInvalid = matchType == VoiceMatchType.REGEX &&
        patternsText.trim().isNotEmpty() &&
        try {
            Regex(patternsText.trim()); false
        } catch (_: Throwable) {
            true
        }

    // 保存阻断校验：执行脚本类型时目标脚本必须存在（定稿决策）
    val scriptMissing = dispatchType == VoiceDispatchType.SCRIPT &&
        payload.trim().isNotEmpty() &&
        ScriptHost.bridge != null &&
        !ScriptHost.bridge!!.exists(payload.trim())

    val canSave = name.isNotBlank() && payload.isNotBlank() &&
        (!regexInvalid) && patternsText.isNotBlank() && !scriptMissing

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
                            cooldownMs = (cooldownSeconds * 1000f).roundToLong()
                                .coerceIn(0L, 60_000L),
                            matchType = matchType,
                            patterns = patterns,
                            dispatchType = dispatchType,
                            scriptExecMode = scriptExecMode,
                            loopTimes = loopTimes.coerceIn(1, 9999),
                            loopDelayMs = loopDelayMs.coerceIn(0L, 86_400_000L),
                            loopIntervalMs = loopIntervalMs.coerceIn(0L, 86_400_000L),
                            timedTaskId = timedTaskId,
                            timedTaskSnapshot = timedTaskSnapshot,
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
            SettingsThemedText(
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
            SettingsThemedText(
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
            SettingsThemedText(
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
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 执行方式（仅执行脚本类型）
            if (dispatchType == VoiceDispatchType.SCRIPT) {
                SettingsThemedText(
                    text = stringResource(R.string.label_voice_dispatch_exec_mode),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ScriptExecMode.IMMEDIATE,
                        ScriptExecMode.LOOP,
                        ScriptExecMode.TIMED
                    ).forEach { mode ->
                        FilterChip(
                            selected = scriptExecMode == mode,
                            onClick = { scriptExecMode = mode },
                            label = {
                                Text(
                                    when (mode) {
                                        ScriptExecMode.IMMEDIATE ->
                                            stringResource(R.string.voice_dispatch_exec_immediate)
                                        ScriptExecMode.LOOP ->
                                            stringResource(R.string.voice_dispatch_exec_loop)
                                        ScriptExecMode.TIMED ->
                                            stringResource(R.string.voice_dispatch_exec_timed)
                                    }
                                )
                            }
                        )
                    }
                }
                if (scriptExecMode == ScriptExecMode.LOOP) {
                    val loopSummary = if (loopConfigDirty || existing != null) {
                        stringResource(
                            R.string.voice_dispatch_loop_params_fmt,
                            loopTimes,
                            formatSeconds(loopDelayMs),
                            formatSeconds(loopIntervalMs)
                        )
                    } else {
                        stringResource(R.string.voice_dispatch_loop_params_none)
                    }
                    SettingsThemedText(
                        text = loopSummary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    androidx.compose.material3.TextButton(
                        enabled = payload.trim().isNotEmpty(),
                        onClick = {
                            val activity = context as? android.app.Activity
                            val bridge = ScriptHost.bridge
                            if (activity == null || bridge == null) return@TextButton
                            val prefill = if (loopConfigDirty || existing != null) {
                                BibiLoopConfig(loopTimes, loopDelayMs, loopIntervalMs)
                            } else {
                                null
                            }
                            val launched = bridge.showLoopConfigDialog(
                                activity,
                                payload.trim(),
                                prefill
                            ) { times, delayMs, intervalMs ->
                                loopTimes = times
                                loopDelayMs = delayMs
                                loopIntervalMs = intervalMs
                                loopConfigDirty = true
                            }
                            if (!launched) {
                                android.widget.Toast.makeText(
                                    context,
                                    R.string.voice_dispatch_script_failed,
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.btn_voice_dispatch_loop_set))
                    }
                }
                if (scriptExecMode == ScriptExecMode.TIMED) {
                    TimedTaskBindingSection(
                        payload = payload.trim(),
                        timedTaskId = timedTaskId,
                        snapshot = timedTaskSnapshot,
                        onBound = { taskId, snap ->
                            timedTaskId = taskId
                            timedTaskSnapshot = snap
                            timedConfigDirty = true
                        },
                        onCleared = {
                            timedTaskId = null
                            timedTaskSnapshot = null
                            timedConfigDirty = true
                        }
                    )
                }
            }

            // 执行目标
            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.label_voice_dispatch_payload)) },
                supportingText = {
                    if (scriptMissing) {
                        Text(
                            text = stringResource(R.string.voice_dispatch_script_missing_error),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(stringResource(R.string.hint_voice_dispatch_payload))
                    }
                },
                isError = scriptMissing,
                singleLine = true,
                trailingIcon = {
                    if (dispatchType == VoiceDispatchType.SCRIPT) {
                        IconButton(onClick = { showScriptPicker = true }) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = stringResource(R.string.cd_voice_dispatch_pick_script)
                            )
                        }
                    }
                }
            )
            if (showScriptPicker) {
                ScriptPickerDialog(
                    onDismiss = { showScriptPicker = false },
                    onSelect = { script ->
                        payload = script.path
                        showScriptPicker = false
                    }
                )
            }

            // 参数模板
            OutlinedTextField(
                value = argsTemplate,
                onValueChange = { argsTemplate = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.label_voice_dispatch_args)) },
                supportingText = { Text(stringResource(R.string.hint_voice_dispatch_args)) },
                singleLine = true
            )

            // 冷却时间滑杆（0-60 秒，步进 0.5；0 = 不限制）
            SettingsThemedText(
                text = if (cooldownSeconds <= 0f) {
                    stringResource(R.string.label_voice_dispatch_cooldown_unlimited)
                } else {
                    stringResource(
                        R.string.label_voice_dispatch_cooldown_seconds,
                        cooldownSeconds.toString()
                    )
                },
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = cooldownSeconds,
                onValueChange = { cooldownSeconds = it },
                valueRange = 0f..60f,
                steps = 119
            )
            SettingsThemedText(
                text = stringResource(R.string.hint_voice_dispatch_cooldown),
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 定时任务绑定区（定稿状态机）：
 * - 未配置：[配置定时任务] → 打开原 TimedTaskSettingActivity 创建页（同文件列表入口）
 * - 返回后：绑定该脚本最新任务 + 抓取快照 + 立即停用（等待语音开启）
 * - 已设置（任务存在）：[修改] 按任务 id 打开编辑页，返回后刷新快照并保持已设置
 * - [删除定时配置]：删除任务（若有）+ 清空绑定与快照
 */
@Composable
private fun TimedTaskBindingSection(
    payload: String,
    timedTaskId: Long?,
    snapshot: TimedTaskSnapshot?,
    onBound: (taskId: Long, snapshot: TimedTaskSnapshot) -> Unit,
    onCleared: () -> Unit
) {
    val context = LocalContext.current

    // 活动状态：相同身份（路径+时间+重复标志）任务或绑定 id 任务存在 = 已设置
    var refreshKey by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    val liveInfo = androidx.compose.runtime.remember(payload, timedTaskId, snapshot, refreshKey) {
        val bridge = ScriptHost.bridge ?: return@remember null
        when {
            snapshot != null ->
                bridge.findTimedTaskByIdentity(payload, snapshot.millis, snapshot.timeFlag)
                    ?: timedTaskId?.let { bridge.getTimedTaskInfo(it) }

            else -> timedTaskId?.let { bridge.getTimedTaskInfo(it) }
        }
    }

    // 返回模式：false=创建模式返回（绑定+快照+停用）；true=编辑模式返回（刷新快照、保持已设置）
    var pendingEditLive by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val bridge = ScriptHost.bridge
        if (bridge == null || payload.isEmpty()) return@rememberLauncherForActivityResult
        val info = bridge.newestTimedTaskForPath(payload)
        if (info != null) {
            if (!pendingEditLive) {
                // 停用：配置完成不立即生效，语音开启后才进入调度
                bridge.removeTimedTask(info.taskId)
            }
            onBound(
                info.taskId,
                TimedTaskSnapshot(millis = info.millis, timeFlag = info.timeFlag, delayMs = info.delayMs)
            )
        }
        refreshKey++
    }

    val summary = when {
        snapshot == null -> stringResource(R.string.voice_dispatch_timed_none)
        else -> {
            val stateText = if (liveInfo != null) {
                stringResource(R.string.voice_dispatch_timed_active)
            } else {
                stringResource(R.string.voice_dispatch_timed_waiting)
            }
            "${formatTimedTaskSnapshot(snapshot)} · $stateText"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsThemedText(
            text = summary,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.TextButton(
                enabled = payload.isNotEmpty() && ScriptHost.bridge != null,
                onClick = {
                    val bridge = ScriptHost.bridge ?: return@TextButton
                    val target = if (liveInfo != null) {
                        // 已设置：进入编辑页
                        pendingEditLive = true
                        bridge.timedTaskEditIntent(liveInfo.taskId)
                    } else {
                        // 未设置：创建页（与文件列表「定时任务」入口一致）
                        pendingEditLive = false
                        bridge.timedTaskCreateIntent(payload)
                    } ?: return@TextButton
                    try {
                        launcher.launch(target)
                    } catch (_: Throwable) {
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (liveInfo != null) R.string.btn_voice_dispatch_timed_edit
                        else R.string.btn_voice_dispatch_timed_config
                    )
                )
            }
            if (snapshot != null) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val bridge = ScriptHost.bridge
                        val info = liveInfo
                        if (bridge != null && info != null) {
                            bridge.removeTimedTask(info.taskId)
                        }
                        onCleared()
                        refreshKey++
                    }
                ) {
                    Text(stringResource(R.string.btn_voice_dispatch_timed_remove))
                }
            }
        }
    }
}

/** 快照 → 展示文本：一次性=具体时间；每天=每天 HH:mm；按位=周几 HH:mm。 */
private fun formatTimedTaskSnapshot(snapshot: TimedTaskSnapshot): String {
    val hour = (snapshot.millis / 3_600_000L).toInt()
    val minute = ((snapshot.millis / 60_000L) % 60L).toInt()
    val timeText = "%02d:%02d".format(hour, minute)
    return when {
        snapshot.timeFlag == 0L -> {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            fmt.format(java.util.Date(snapshot.millis))
        }

        snapshot.timeFlag == 0x7FL -> "每天 $timeText"

        else -> {
            // TimedTask 位定义：0x1=周日、0x2=周一 … 0x40=周六
            val names = listOf("日", "一", "二", "三", "四", "五", "六")
            val days = (0..6).filter { (snapshot.timeFlag shr it) and 1L == 1L }
                .joinToString("、") { "周${names[it]}" }
            "$days $timeText"
        }
    }
}

/** 毫秒 → 秒显示文本（整数秒不带小数点，与循环运行弹窗输入单位一致）。 */
private fun formatSeconds(ms: Long): String {
    if (ms % 1000L == 0L) return (ms / 1000L).toString()
    return (ms / 1000.0).toString()
}
