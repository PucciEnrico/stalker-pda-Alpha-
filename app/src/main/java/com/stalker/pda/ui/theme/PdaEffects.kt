package com.stalker.pda.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/** Semi-transparent horizontal scanlines drawn over the content. */
fun Modifier.scanlineOverlay(lineSpacing: Float = 4f, lineAlpha: Float = 0.08f): Modifier =
    this.drawWithContent {
        drawContent()
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = Color.Black.copy(alpha = lineAlpha),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = lineSpacing * 0.5f
            )
            y += lineSpacing
        }
    }

/** Slow, occasional phosphor flicker. Value is read in the graphics layer, so nothing recomposes. */
@Composable
fun rememberFlickerAlpha(): State<Float> {
    val transition = rememberInfiniteTransition(label = "flicker")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1600
                1f at 0
                0.95f at 60
                1f at 120
                1f at 1000
                0.93f at 1040
                0.98f at 1080
                1f at 1140
                1f at 1600
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "flickerAlpha"
    )
}

fun Modifier.crtFlicker(flicker: State<Float>): Modifier =
    this.graphicsLayer {
        alpha = flicker.value
        clip = true
    }

/** Darkened corners like an old CRT. */
fun Modifier.crtVignette(): Modifier = this.drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
            center = Offset(size.width / 2, size.height / 2),
            radius = maxOf(size.width, size.height) * 0.7f
        )
    )
}

@Composable
fun PdaScreenEffects(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val flicker = rememberFlickerAlpha()
    Box(
        modifier = modifier
            .crtFlicker(flicker)
            .scanlineOverlay()
            .crtVignette()
    ) {
        content()
    }
}
