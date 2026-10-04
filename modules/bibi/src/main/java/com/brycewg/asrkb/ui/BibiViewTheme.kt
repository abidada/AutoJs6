/**
 * View 系 UI 的主题解析。
 *
 * 色板从 app 宿主主题色种子(经 AppThemeColorBridge)以 material-color-utilities
 * SchemeTonalSpot 生成,与 Compose 通道 Miuix ThemeController(MonetSystem+keyColor)
 * 使用同一算法,保证悬浮层与设置页观感一致。
 *
 * 归属模块：ui
 */
package com.brycewg.asrkb.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.brycewg.asrkb.ui.theme.AppThemeColorBridge
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot

internal data class BibiViewTheme(
    val isDark: Boolean,
    val keyboardBackground: Int,
    val keyBackground: Int,
    val keyContent: Int,
    val panelBackground: Int,
    val panelContent: Int,
    val panelSummary: Int,
    val primary: Int,
    val onPrimary: Int,
    val micContainer: Int,
    val micContent: Int,
    val floatingIcon: Int,
    val ripple: Int,
    val error: Int,
    val success: Int,
    val iconKeyRadiusDp: Float,
    val rectKeyRadiusDp: Float,
    val panelRadiusDp: Float,
    val keyInsetDp: Int,
    val menuItemBackground: Int
)

internal object BibiViewThemes {

    fun resolve(context: Context): BibiViewTheme {
        AppThemeColorBridge.initialize(context)
        val isDark = AppThemeColorBridge.isDark(context)
        val scheme = SchemeTonalSpot(
            sourceColorHct = Hct.fromInt(AppThemeColorBridge.seedColor.intValue),
            isDark = isDark,
            contrastLevel = 0.0
        )

        val primary = scheme.primary
        val onPrimary = scheme.onPrimary
        // 键位/菜单容器取 surfaceContainerHigh(对应 miuix 映射的 secondaryVariant 槽位)
        val keyContainer = scheme.surfaceContainerHigh

        return BibiViewTheme(
            isDark = isDark,
            keyboardBackground = scheme.background,
            keyBackground = keyContainer,
            keyContent = scheme.onSurface,
            panelBackground = scheme.surfaceContainer,
            panelContent = scheme.onSurface,
            panelSummary = scheme.onSurfaceVariant,
            primary = primary,
            onPrimary = onPrimary,
            micContainer = primary,
            micContent = onPrimary,
            floatingIcon = primary,
            ripple = withAlpha(primary, 0.12f),
            error = scheme.error,
            success = if (isDark) 0xFF2ECC71.toInt() else 0xFF1B9E4B.toInt(),
            iconKeyRadiusDp = 14f,
            rectKeyRadiusDp = 18f,
            panelRadiusDp = 22f,
            keyInsetDp = 0,
            menuItemBackground = keyContainer
        )
    }

    fun roundedRipple(
        context: Context,
        @ColorInt color: Int,
        @ColorInt rippleColor: Int,
        radiusDp: Float,
        insetDp: Int = 2
    ): Drawable {
        val radius = dp(context, radiusDp)
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        return InsetDrawable(
            RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask),
            dp(context, insetDp.toFloat()).toInt()
        )
    }

    fun roundedRect(context: Context, @ColorInt color: Int, radiusDp: Float): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(context, radiusDp)
    }

    fun dot(@ColorInt color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    @ColorInt
    private fun withAlpha(@ColorInt color: Int, alpha: Float): Int = ColorUtils.setAlphaComponent(color, (alpha * 255).toInt().coerceIn(0, 255))

    private fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density
}
