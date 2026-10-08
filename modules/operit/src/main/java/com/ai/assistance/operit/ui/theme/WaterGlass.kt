package com.ai.assistance.operit.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Operit port: the liquid/backdrop glass engines are trimmed (Java 21 bytecode vs host JDK 17
// kapt stubs); both glass modifiers keep their exact signatures and always render the
// translucent fallback (shadow + border + tint + gloss).
val LocalWaterGlassState = compositionLocalOf<Any?> { null }

private const val WaterGlassMinApi = Build.VERSION_CODES.TIRAMISU

fun isWaterGlassSupported(): Boolean = Build.VERSION.SDK_INT >= WaterGlassMinApi

@Composable
fun Modifier.waterGlass(
    enabled: Boolean,
    shape: Shape = RoundedCornerShape(0.dp),
    containerColor: Color,
    shadowElevation: Dp = 14.dp,
    borderWidth: Dp = 1.dp,
    overlayAlphaBoost: Float = 0f,
): Modifier {
    if (!enabled) {
        return this
    }

    val isLightGlass = containerColor.luminance() >= 0.5f
    val tintAlpha = if (isLightGlass) 0.09f else 0.16f
    val surfaceTint = containerColor.copy(alpha = (tintAlpha + overlayAlphaBoost).coerceIn(0f, 0.56f))
    val borderColor =
        if (isLightGlass) {
            Color.White.copy(alpha = 0.18f)
        } else {
            Color.White.copy(alpha = 0.10f)
        }
    val shadowColor =
        if (isLightGlass) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.Black.copy(alpha = 0.18f)
        }
    val gloss =
        if (isLightGlass) {
            Color.White.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.05f)
        }

    return this
        .shadow(
            elevation = shadowElevation,
            shape = shape,
            clip = false,
            ambientColor = shadowColor,
            spotColor = shadowColor,
        )
        .border(width = borderWidth, color = borderColor, shape = shape)
        .background(color = surfaceTint, shape = shape)
        .drawWithContent {
            drawContent()
            drawRect(gloss)
        }
}
