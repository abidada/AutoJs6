/**
 * 设置页滑块数值的精确输入对话框。
 *
 * 点击滑块标题右侧的数值时弹出，允许直接键入整数（毫秒 / 秒），
 * 输入框右侧提供 +/- 快捷按钮按 1 步进调整并钳位到 [min, max]。
 * 校验逻辑由调用方通过 onConfirm 回调提供：返回 null 表示校验通过并关闭，
 * 返回非空字符串表示错误提示，对话框保持打开并展示该提示。
 * 采用 Material3 对话框，独立于列表主题，兼容 Material / Miuix 两种模式。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * 数字输入对话框。
 *
 * @param title 对话框标题（一般为设置项名称）
 * @param initialValueText 打开时预填的数值文本（单位与输入单位一致）
 * @param unitLabel 单位提示，展示为输入框 label（如「毫秒」「秒」）
 * @param supportingText 输入框下方的取值范围说明
 * @param min +/- 快捷按钮的下限（与输入单位一致）
 * @param max +/- 快捷按钮的上限（与输入单位一致）
 * @param onConfirm 校验并提交，返回错误文案或 null（null 代表成功并关闭）
 * @param onDismiss 关闭对话框
 */
@Composable
internal fun SettingsNumberInputDialog(
    title: String,
    initialValueText: String,
    unitLabel: String,
    supportingText: String?,
    min: Int,
    max: Int,
    onConfirm: (String) -> String?,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValueText) }
    var error by remember { mutableStateOf<String?>(null) }

    fun adjust(delta: Int) {
        // 文本为空或非法时不动作，需先键入合法数字；越界值先钳回区间再 ±1
        val current = text.toIntOrNull() ?: return
        text = (current.coerceIn(min, max) + delta).coerceIn(min, max).toString()
        error = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = text,
                        // 仅允许非负整数，过滤掉符号与非数字字符
                        onValueChange = { next ->
                            text = next.filter { it.isDigit() }
                            error = null
                        },
                        singleLine = true,
                        isError = error != null,
                        label = { Text(unitLabel) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = { adjust(1) }) {
                            Icon(Icons.Rounded.Add, contentDescription = "+")
                        }
                        FilledTonalIconButton(onClick = { adjust(-1) }) {
                            Icon(Icons.Rounded.Remove, contentDescription = "-")
                        }
                    }
                }
                val message = error ?: supportingText
                if (message != null) {
                    Text(
                        text = message,
                        color = if (error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val errorMessage = onConfirm(text)
                if (errorMessage == null) onDismiss() else error = errorMessage
            }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
