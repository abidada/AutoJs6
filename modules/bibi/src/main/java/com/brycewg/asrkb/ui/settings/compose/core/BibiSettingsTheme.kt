/**
 * 设置页 Compose 主题桥接。
 *
 * 单引擎 Miuix;主题色种子与暗色态全部来自 app 宿主(经 AppThemeColorBridge),
 * bibi 自身不再持有任何主题设置。
 *
 * 归属模块：ui/settings/compose/core
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.core

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.brycewg.asrkb.ui.theme.AppThemeColorBridge
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun BibiSettingsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    AppThemeColorBridge.initialize(context)

    val seedColor = AppThemeColorBridge.seedColor.intValue
    val isDark = AppThemeColorBridge.darkOverride.value ?: isSystemInDarkTheme()

    SyncSystemBarAppearance(isDark)

    // M3 底座:部分组件仍读 MaterialTheme,喂种子色 primary 保持与 Miuix 同源观感
    val materialColorScheme = remember(seedColor, isDark) {
        val primary = Color(seedColor)
        if (isDark) darkColorScheme(primary = primary) else lightColorScheme(primary = primary)
    }

    CompositionLocalProvider(LocalBibiSettingsDark provides isDark) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            shapes = bibiMaterialShapes
        ) {
            // MonetSystem + keyColor:miuix 内部用 material-color-utilities 从种子色生成全套色板
            val miuixThemeController = remember(seedColor, isDark) {
                ThemeController(
                    ColorSchemeMode.MonetSystem,
                    keyColor = Color(seedColor),
                    isDark = isDark
                )
            }
            MiuixTheme(
                controller = miuixThemeController
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MiuixTheme.colorScheme.onBackground
                ) {
                    MiuixScaffold(
                        contentWindowInsets = WindowInsets(0.dp)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

private val bibiMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
private fun SyncSystemBarAppearance(isDark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return

    SideEffect {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }
}

/** bibi 设置页暗色态;值由 BibiSettingsTheme 统一解析(app 夜模式覆盖 > 系统配置)。 */
val LocalBibiSettingsDark = staticCompositionLocalOf { false }
