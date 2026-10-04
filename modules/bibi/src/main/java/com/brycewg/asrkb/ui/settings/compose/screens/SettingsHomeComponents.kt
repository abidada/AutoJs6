/**
 * 设置首页三 Tab 的脚手架、列表与分区组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.SettingsSection
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar

@Composable
internal fun SettingsHomeScaffold(
    tabs: List<SettingsHomeTab>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val selectTabWithHaptic = { index: Int ->
        hapticTap()
        onSelectTab(index)
    }
    MiuixHomeScaffold(tabs, selectedTab, selectTabWithHaptic, content)
}

@Composable
private fun MiuixHomeScaffold(
    tabs: List<SettingsHomeTab>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    MiuixScaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            MiuixNavigationBar {
                tabs.forEachIndexed { index, tab ->
                    MiuixNavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { if (selectedTab != index) onSelectTab(index) },
                        icon = tab.miuixIcon,
                        label = stringResource(tab.titleRes)
                    )
                }
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
        content = { innerPadding ->
            content(
                innerPadding,
                Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            )
        }
    )
}

@Composable
internal fun SettingsSectionList(
    sections: List<SettingsSection>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding
) {
    SettingsLazyColumn(
        modifier = Modifier.fillMaxSize(),
        miuixScrollModifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
    ) {
        settingsSectionItems(sections)
    }
}

private fun LazyListScope.settingsSectionItems(
    sections: List<SettingsSection>,
) {
    sections.forEach { section ->
        item(key = section.id) {
            SettingsHomeSection(section = section)
        }
    }
}

@Composable
private fun SettingsHomeSection(
    section: SettingsSection,
) {
    SettingsSectionContainer(titleRes = section.titleRes) {
        section.entries.forEachIndexed { index, entry ->
            key(entry.id) {
                SettingsPreference(
                    entry = entry,
                    index = index,
                    count = section.entries.size
                )
            }
        }
    }
}

internal data class SettingsHomeTab(
    val titleRes: Int,
    val miuixIcon: ImageVector
)

internal fun settingsHomeTabs(): List<SettingsHomeTab> = listOf(
    SettingsHomeTab(
        titleRes = R.string.settings_tab_input,
        miuixIcon = Icons.Rounded.Keyboard
    ),
    SettingsHomeTab(
        titleRes = R.string.settings_tab_smart,
        miuixIcon = Icons.Rounded.AutoAwesome
    ),
    SettingsHomeTab(
        titleRes = R.string.settings_tab_system,
        miuixIcon = Icons.Rounded.Settings
    )
)
