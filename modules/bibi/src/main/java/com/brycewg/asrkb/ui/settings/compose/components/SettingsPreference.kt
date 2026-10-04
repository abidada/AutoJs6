/**
 * 设置项渲染入口。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHighlightTarget
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.miuix.MiuixSettingsEntry
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsPreference(
    entry: SettingsEntry,
    index: Int = 0,
    count: Int = 1
) {
    SettingsHighlightContainer(
        entryId = entry.id
    ) {
        MiuixSettingsEntry(entry)
    }
}

@Composable
fun SettingsPreferenceGroup(vararg entries: SettingsEntry?) {
    val visibleCount = entries.count { it != null }
    var visibleIndex = 0
    entries.forEach { entry ->
        if (entry != null) {
            val index = visibleIndex
            key(entry.id) {
                SettingsPreference(
                    entry = entry,
                    index = index,
                    count = visibleCount
                )
            }
            visibleIndex++
        }
    }
}

@Composable
internal fun SettingsHighlightContainer(
    entryId: String,
    content: @Composable () -> Unit
) {
    val highlightTargetId = LocalSettingsHighlightTarget.current
    if (highlightTargetId == null) {
        content()
        return
    }

    var active by remember(entryId, highlightTargetId) {
        mutableStateOf(highlightTargetId == entryId)
    }
    LaunchedEffect(entryId, highlightTargetId) {
        active = highlightTargetId == entryId
        if (active) {
            delay(HIGHLIGHT_DURATION_MILLIS)
            active = false
        }
    }

    val highlightColor = MiuixTheme.colorScheme.primary.copy(alpha = HIGHLIGHT_ALPHA)
    val color by animateColorAsState(
        targetValue = if (active) highlightColor else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(durationMillis = HIGHLIGHT_FADE_MILLIS),
        label = "SettingsSearchHighlight"
    )
    Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .background(
                color = color,
                shape = RoundedCornerShape(SettingsLayoutMetrics.MaterialSectionShape)
            )
    ) {
        content()
    }
}

private const val HIGHLIGHT_DURATION_MILLIS = 1800L
private const val HIGHLIGHT_FADE_MILLIS = 260
private const val HIGHLIGHT_ALPHA = 0.38f
