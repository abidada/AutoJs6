/**
 * 唤醒词管理页：选择生效词(全部/预置/自定义)+ 自定义词的添加/编辑/删除。
 *
 * 自定义词由汉字经 pinyin4j 转带调拼音生成关键词行(WakeWordStore),选中性即时生效。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsThemedText
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.wake.WakeWordStore

private data class WakeWordEntry(
    val name: String,
    val isCustom: Boolean
)

@Composable
internal fun WakeWordManagerScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    var selected by remember(context) { mutableStateOf(prefs.wakeWordSelected) }
    var customs by remember(context) { mutableStateOf(WakeWordStore.loadCustom(context)) }
    var dialogEditing by remember { mutableStateOf<String?>(null) } // null=关闭, ""=新建, 其他=编辑旧名
    var dialogText by remember { mutableStateOf("") }

    val presetNames = remember(context) { WakeWordStore.presetNames(context) }
    val entries = remember(customs, presetNames) {
        buildList {
            add(WakeWordEntry("", isCustom = false))
            presetNames.forEach { add(WakeWordEntry(it, isCustom = false)) }
            customs.forEach { add(WakeWordEntry(it.name, isCustom = true)) }
        }
    }

    fun select(name: String) {
        selected = name
        prefs.wakeWordSelected = name
        restartServiceIfEnabled(context, prefs)
    }

    fun saveCustom(oldName: String?, text: String) {
        val trimmed = text.trim()
        if (!Regex("^[\u4e00-\u9fa5]{2,5}$").matches(trimmed)) {
            Toast.makeText(context, R.string.wake_word_input_hint, Toast.LENGTH_SHORT).show()
            return
        }
        if (WakeWordStore.textToKeywordLine(context, trimmed) == null) {
            Toast.makeText(context, R.string.wake_word_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val conflict = trimmed != oldName &&
            (presetNames.contains(trimmed) || customs.any { it.name == trimmed })
        if (conflict) {
            Toast.makeText(context, R.string.wake_word_exists, Toast.LENGTH_SHORT).show()
            return
        }
        val tokens = WakeWordStore.textToKeywordLine(context, trimmed)!!
            .substringBeforeLast('@').trim()
        val next = customs.toMutableList()
        val idx = next.indexOfFirst { it.name == oldName }
        if (idx >= 0) {
            next[idx] = com.brycewg.asrkb.wake.CustomWakeWord(name = trimmed, tokens = tokens)
            if (selected == oldName) {
                selected = trimmed
                prefs.wakeWordSelected = trimmed
            }
        } else {
            next.add(com.brycewg.asrkb.wake.CustomWakeWord(name = trimmed, tokens = tokens))
        }
        customs = next
        WakeWordStore.saveCustom(context, next)
        restartServiceIfEnabled(context, prefs)
        dialogEditing = null
    }

    fun deleteCustom(name: String) {
        val next = customs.filterNot { it.name == name }
        customs = next
        WakeWordStore.saveCustom(context, next)
        if (selected == name) {
            selected = ""
            prefs.wakeWordSelected = ""
        }
        restartServiceIfEnabled(context, prefs)
    }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_wake_word_manager,
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                dialogText = ""
                dialogEditing = ""
            }) {
                Text(stringResource(R.string.btn_wake_word_add))
            }
        }
    ) { innerPadding, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SettingsLayoutMetrics.pageContentPadding(innerPadding)),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected == entry.name,
                        onClick = { select(entry.name) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        SettingsThemedText(
                            text = if (entry.name.isBlank()) {
                                stringResource(R.string.wake_word_all)
                            } else {
                                entry.name
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (entry.isCustom) {
                            SettingsThemedText(
                                text = stringResource(R.string.wake_word_custom_tag),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (entry.isCustom) {
                        TextButton(onClick = {
                            dialogText = entry.name
                            dialogEditing = entry.name
                        }) {
                            Text(stringResource(R.string.btn_voice_dispatch_edit))
                        }
                        TextButton(onClick = { deleteCustom(entry.name) }) {
                            Text(
                                text = stringResource(R.string.btn_voice_dispatch_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    if (dialogEditing != null) {
        AlertDialog(
            onDismissRequest = { dialogEditing = null },
            title = {
                Text(
                    text = if (dialogEditing!!.isBlank()) {
                        stringResource(R.string.btn_wake_word_add)
                    } else {
                        stringResource(R.string.title_wake_word_edit)
                    }
                )
            },
            text = {
                OutlinedTextField(
                    value = dialogText,
                    onValueChange = { dialogText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.wake_word_input_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { saveCustom(dialogEditing, dialogText) }) {
                    Text(stringResource(R.string.btn_voice_dispatch_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogEditing = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private fun restartServiceIfEnabled(context: android.content.Context, prefs: Prefs) {
    try {
        if (prefs.wakeWordEnabled) {
            com.brycewg.asrkb.wake.WakeWordService.stop(context)
            com.brycewg.asrkb.wake.WakeWordService.start(context)
        }
    } catch (t: Throwable) {
        // 服务重启失败不影响设置保存
    }
}
