/**
 * 脚本选择对话框：搜索筛选 + 工作目录递归脚本列表。
 *
 * 采用 Material3 对话框，独立于列表主题，兼容 Material / Miuix 两种模式
 * （同 SettingsNumberInputDialog 先例）。列表数据经 [com.brycewg.asrkb.host.ScriptHost]
 * 桥接获取，扫描在 IO 线程执行。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.host.BibiScriptInfo
import com.brycewg.asrkb.host.ScriptHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ScriptPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (BibiScriptInfo) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var scripts by remember { mutableStateOf<List<BibiScriptInfo>?>(null) }

    LaunchedEffect(Unit) {
        // 扫描工作目录含磁盘 IO，放 IO 线程；bridge 未注册时给空列表走空态
        scripts = withContext(Dispatchers.IO) {
            try {
                ScriptHost.bridge?.listScripts().orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    val filtered = remember(scripts, query) {
        val all = scripts.orEmpty()
        val q = query.trim()
        if (q.isEmpty()) {
            all
        } else {
            all.filter {
                it.name.contains(q, true) || it.relativeDir.contains(q, true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_voice_dispatch_script_picker)) },
        text = {
            Column {
                SettingsSearchField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.hint_voice_dispatch_script_search),
                    uiMode = com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode.Material
                )
                when {
                    scripts == null -> {
                        Text(
                            text = "",
                            modifier = Modifier.heightIn(min = 120.dp)
                        )
                    }

                    filtered.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.voice_dispatch_script_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp)
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                        ) {
                            items(filtered, key = { it.path }) { script ->
                                ScriptPickerItem(
                                    script = script,
                                    onClick = { onSelect(script) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun ScriptPickerItem(
    script: BibiScriptInfo,
    onClick: () -> Unit
) {
    androidx.compose.material3.ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = script.name.removeSuffix(".js").removeSuffix(".auto"),
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            val dir = script.relativeDir
            if (dir.isNotEmpty()) {
                Text(
                    text = dir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
