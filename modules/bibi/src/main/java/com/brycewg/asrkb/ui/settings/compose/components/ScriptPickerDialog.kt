/**
 * 脚本选择对话框：文件浏览器形态（对齐主界面文件列表）。
 *
 * - 默认根 = 当前工作目录，展示该目录下全部文件夹与文件（文件夹在前）；
 * - 点文件夹进入（面包屑 + 返回上级），不越出工作目录；
 * - 点 .js/.auto 脚本选中回填；其余文件置灰不可选；
 * - 搜索框：输入即切换为工作目录内全局递归搜索（名称/路径），清空恢复浏览。
 *
 * 采用 Material3 对话框，独立于列表主题，兼容 Material / Miuix 两种模式。
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.host.BibiScriptInfo
import com.brycewg.asrkb.host.ScriptHost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ScriptPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (BibiScriptInfo) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var rootDir by remember { mutableStateOf("") }
    var currentDir by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<BibiScriptInfo>?>(null) }
    var searchResults by remember { mutableStateOf<List<BibiScriptInfo>?>(null) }

    // 初始定位到工作目录根
    LaunchedEffect(Unit) {
        val bridge = ScriptHost.bridge ?: return@LaunchedEffect
        val root = withContext(Dispatchers.IO) {
            try {
                bridge.workingDirectory()
            } catch (_: Throwable) {
                ""
            }
        }
        if (root.isNotEmpty()) {
            rootDir = root
            currentDir = root
        }
    }

    // 浏览模式：加载当前目录
    LaunchedEffect(currentDir) {
        if (currentDir.isEmpty()) return@LaunchedEffect
        val bridge = ScriptHost.bridge ?: return@LaunchedEffect
        entries = withContext(Dispatchers.IO) {
            try {
                bridge.listDirectory(currentDir)
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    // 搜索模式：工作目录内全局递归
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchResults = null
            return@LaunchedEffect
        }
        val bridge = ScriptHost.bridge ?: return@LaunchedEffect
        searchResults = withContext(Dispatchers.IO) {
            try {
                bridge.listScripts().filter {
                    it.name.contains(q, true) || it.relativeDir.contains(q, true)
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    val rootName = remember(rootDir) {
        if (rootDir.isEmpty()) "..." else File(rootDir).name.ifEmpty { rootDir }
    }
    val breadcrumb = remember(currentDir, rootDir) {
        if (currentDir == rootDir || rootDir.isEmpty()) {
            rootName
        } else {
            currentDir.removePrefix(rootDir).trimStart('/', '\\').let { "$rootName/$it" }
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
                if (query.trim().isEmpty()) {
                    // 浏览模式：面包屑 + 返回上级
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            enabled = currentDir != rootDir && currentDir.isNotEmpty(),
                            onClick = {
                                val parent = File(currentDir).parentFile
                                if (parent != null && parent.absolutePath.startsWith(rootDir)) {
                                    currentDir = parent.absolutePath
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.voice_dispatch_parent_dir)
                            )
                        }
                        Text(
                            text = breadcrumb,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                val list: List<BibiScriptInfo>? = if (query.trim().isEmpty()) entries else searchResults
                when {
                    list == null -> {
                        Text(text = "", modifier = Modifier.heightIn(min = 160.dp))
                    }

                    list.isEmpty() -> {
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
                                .heightIn(max = 400.dp)
                        ) {
                            items(list, key = { it.path }) { entry ->
                                PickerEntryRow(
                                    entry = entry,
                                    onClick = {
                                        when {
                                            entry.isDirectory -> currentDir = entry.path
                                            entry.isScript -> onSelect(entry)
                                        }
                                    }
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
private fun PickerEntryRow(
    entry: BibiScriptInfo,
    onClick: () -> Unit
) {
    val selectable = entry.isDirectory || entry.isScript
    val enabledColor = MaterialTheme.colorScheme.onSurface
    val disabledColor = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectable) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        leadingContent = {
            Icon(
                imageVector = when {
                    entry.isDirectory -> Icons.Rounded.Folder
                    entry.isScript -> Icons.Rounded.Code
                    else -> Icons.Rounded.Description
                },
                contentDescription = null,
                tint = if (selectable) enabledColor else disabledColor,
                modifier = Modifier.size(24.dp)
            )
        },
        headlineContent = {
            Text(
                text = entry.displayName(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (selectable) enabledColor else disabledColor
            )
        },
        supportingContent = {
            val secondary = if (entry.isDirectory) {
                formatDate(entry.lastModified)
            } else {
                "${formatSize(entry.size)} · ${formatDate(entry.lastModified)}"
            }
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = disabledColor
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** 展示名：脚本文件去 .js/.auto 后缀，与文件列表口径一致。 */
private fun BibiScriptInfo.displayName(): String =
    if (isDirectory) {
        name
    } else {
        name.removeSuffix(".js").removeSuffix(".auto").ifEmpty { name }
    }

private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
