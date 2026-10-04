/**
 * Compose 设置搜索页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSearchField
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.search.SettingsSearchEntry
import com.brycewg.asrkb.ui.settings.search.SettingsSearchIndex
import com.brycewg.asrkb.ui.settings.search.SettingsSearchMatcher
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsSearchScreen(
    onBack: () -> Unit,
    onOpenEntry: (SettingsSearchEntry) -> Unit
) {
    val context = LocalContext.current
    val rows = remember(context) {
        SettingsSearchMatcher.buildRows(
            entries = SettingsSearchIndex.get(context),
            screenTitleProvider = context::getString
        )
    }
    var query by remember { mutableStateOf("") }
    val results = remember(rows, query) {
        SettingsSearchMatcher.filter(rows, query)
    }

    SettingsSearchScaffold(onBack = onBack) { innerPadding, scrollModifier ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = SettingsLayoutMetrics.PageHorizontalPadding)
                .padding(top = SettingsLayoutMetrics.SearchTopPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = dimensionResource(R.dimen.settings_form_max_width))
                    .fillMaxWidth()
            ) {
                SettingsSearchField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.hint_settings_search),
                    autoFocus = true
                )
                if (results.isEmpty()) {
                    EmptySearchState()
                } else {
                    SearchResultList(
                        entries = results,
                        scrollModifier = scrollModifier,
                        onOpenEntry = onOpenEntry
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchScaffold(
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    SettingsDetailScaffold(
        titleRes = R.string.title_settings_search,
        onBack = onBack,
        content = content
    )
}

@Composable
private fun SearchResultList(
    entries: List<SettingsSearchEntry>,
    scrollModifier: Modifier,
    onOpenEntry: (SettingsSearchEntry) -> Unit
) {
    SettingsLazyColumn(
        modifier = Modifier.fillMaxSize(),
        miuixScrollModifier = scrollModifier,
        contentPadding = PaddingValues(vertical = SettingsLayoutMetrics.SearchListVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SearchResultSpacing)
    ) {
        items(
            items = entries,
            key = { entry ->
                buildString {
                    append(entry.composeRoute?.id.orEmpty())
                    append('#')
                    append(entry.title)
                    if (entry.sectionPath.isNotEmpty()) {
                        append('#')
                        append(entry.sectionPath.joinToString(">"))
                    }
                    if (!entry.forceAsrVendorId.isNullOrBlank()) {
                        append("#asr=")
                        append(entry.forceAsrVendorId)
                    }
                    if (!entry.forceLlmVendorId.isNullOrBlank()) {
                        append("#llm=")
                        append(entry.forceLlmVendorId)
                    }
                }
            }
        ) { entry ->
            SearchResultItem(
                entry = entry,
                onClick = { onOpenEntry(entry) }
            )
        }
    }
}

@Composable
private fun SearchResultItem(
    entry: SettingsSearchEntry,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val hapticTap = LocalSettingsHapticTap.current
    val clickWithHaptic = {
        hapticTap()
        onClick()
    }
    val subtitle = remember(entry, context) {
        SettingsSearchMatcher.subtitle(entry, context::getString)
    }
    // 使用 Card(onClick) 让按压遮罩画在内部 clip 之后，避免直角灰遮罩溢出圆角。
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        showIndication = true,
        onClick = clickWithHaptic
    ) {
        SearchResultText(entry.title, subtitle)
    }
}

@Composable
private fun SearchResultText(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier.padding(SettingsLayoutMetrics.SearchResultContentPadding),
        verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SearchResultTextSpacing)
    ) {
        MiuixText(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        MiuixText(
            text = subtitle,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptySearchState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SettingsLayoutMetrics.SearchEmptyHorizontalPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MiuixText(
            text = stringResource(R.string.text_settings_search_empty),
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}
