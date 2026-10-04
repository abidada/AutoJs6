/**
 * Compose 设置详情页脚手架。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults as MiuixTopAppBarDefaults

@Composable
internal fun SettingsDetailScaffold(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    /** 标题水平内边距覆盖（默认用库常量 26dp；顶栏两侧内容密集的页面可传小值防标题截断）。 */
    titlePadding: Dp? = null,
    /** 返回键右侧的前导操作区（如「新建」按钮）；标题居中行为不受影响 */
    leadingActions: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    val backLabel = stringResource(R.string.cd_clipboard_back)
    val hapticTap = LocalSettingsHapticTap.current
    val backWithHaptic = {
        hapticTap()
        onBack()
    }
    val insets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
        .union(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))

    val scrollBehavior = MiuixScrollBehavior()
    MiuixScaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(titleRes),
                scrollBehavior = scrollBehavior,
                titlePadding = titlePadding ?: MiuixTopAppBarDefaults.TitlePadding,
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MiuixIconButton(onClick = backWithHaptic) {
                            MiuixIcon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = backLabel
                            )
                        }
                        leadingActions()
                    }
                },
                actions = { actions() }
            )
        },
        bottomBar = bottomBar,
        popupHost = { },
        contentWindowInsets = insets,
        content = { innerPadding ->
            content(
                innerPadding,
                Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            )
        }
    )
}
