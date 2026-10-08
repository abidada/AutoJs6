package com.ai.assistance.operit.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Operit port: liquid-glass engine trimmed; see WaterGlass.kt header.
val LocalLiquidGlassBackdrop = compositionLocalOf<Any?> { null }

private const val LiquidGlassMinApi = Build.VERSION_CODES.TIRAMISU

fun isLiquidGlassSupported(): Boolean = Build.VERSION.SDK_INT >= LiquidGlassMinApi

@Composable
fun Modifier.liquidGlass(
    enabled: Boolean,
    shape: CornerBasedShape = RoundedCornerShape(0.dp),
    containerColor: Color,
    shadowElevation: Dp = 14.dp,
    borderWidth: Dp = 1.dp,
    blurRadius: Dp = 10.dp,
    overlayAlphaBoost: Float = 0f,
    enableLens: Boolean = true,
): Modifier {
    if (!enabled) {
        return this
    }

    val isLightGlass = containerColor.luminance() >= 0.5f
    val baseTintAlpha = if (isLightGlass) 0.16f else 0.23f
    val surfaceTint =
        containerColor.copy(alpha = (baseTintAlpha + overlayAlphaBoost).coerceIn(0f, 0.48f))
    val edgeWidth = borderWidth.coerceAtLeast(0.2.dp)
    val shadowRadius = shadowElevation.coerceAtLeast(12.dp)
    val shadowColor =
        if (isLightGlass) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.Black.copy(alpha = 0.18f)
        }
    val borderColor =
        if (isLightGlass) {
            Color.White.copy(alpha = 0.28f)
        } else {
            Color.White.copy(alpha = 0.16f)
        }
    val gloss =
        if (isLightGlass) {
            Color.White.copy(alpha = 0.12f)
        } else {
            Color.White.copy(alpha = 0.06f)
        }

    return this
        .shadow(
            elevation = shadowRadius,
            shape = shape,
            clip = false,
            ambientColor = shadowColor,
            spotColor = shadowColor,
        )
        .border(width = edgeWidth, color = borderColor, shape = shape)
        .background(color = surfaceTint, shape = shape)
        .drawWithContent {
            drawContent()
            drawRect(gloss)
        }
}
