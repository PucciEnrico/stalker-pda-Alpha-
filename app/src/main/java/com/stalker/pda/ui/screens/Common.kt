package com.stalker.pda.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.ui.theme.scanlineOverlay
import kotlin.math.sin

/** Two panels side by side (default 35% / 65%). */
@Composable
fun SplitPane(
    leftWeight: Float,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxSize().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.weight(leftWeight).fillMaxHeight()) { left() }
        Box(modifier = Modifier.weight(1f - leftWeight).fillMaxHeight()) { right() }
    }
}

/** Green CRT oscilloscope. [level] 0..1 scales the amplitude (microphone level while recording). */
@Composable
fun OscilloscopeDisplay(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    level: Float = 0.6f
) {
    val transition = rememberInfiniteTransition(label = "oscillo")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val accent = PdaColors.Green

    Canvas(
        modifier = modifier
            .background(PdaColors.ScreenBg)
            .border(1.dp, accent.copy(alpha = 0.3f))
    ) {
        val centerY = size.height / 2
        if (!isActive) {
            drawLine(accent.copy(alpha = 0.3f), Offset(0f, centerY), Offset(size.width, centerY), 1.5f)
            return@Canvas
        }
        val twoPi = 6.2831855f
        val amplitude = size.height * 0.42f * level.coerceIn(0.05f, 1f)
        val path = Path()
        var x = 0f
        var first = true
        while (x <= size.width) {
            val n = x / size.width
            val w1 = sin(phase + n * 3f * twoPi)
            val w2 = sin(phase * 2f + n * 6f * twoPi)
            val w3 = sin(phase + n * 1.5f * twoPi)
            val y = centerY + amplitude * (0.6f * w1 + 0.3f * w2 + 0.1f * w3)
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            x += 4f
        }
        drawPath(path, accent.copy(alpha = 0.15f), style = Stroke(width = 5f))
        drawPath(path, accent.copy(alpha = 0.9f), style = Stroke(width = 1.6f))
    }
}

@Composable
fun BootScreen(modifier: Modifier = Modifier) {
    val lines = listOf(
        "S.T.A.L.K.E.R. PDA v2.31",
        "ЗАВАНТАЖЕННЯ ЯДРА СИСТЕМИ...",
        "МОДУЛЬ GPS.................. OK",
        "ТАКТИЧНА БАЗА ДАНИХ......... OK",
        "ЛОКАЛЬНА МЕРЕЖА............. OK",
        "ДАТЧИКИ АНОМАЛІЙ............ OK",
        "",
        "УВАГА: ЗОНА ВИКИДУ",
        "ДІЙ ОБЕРЕЖНО",
        "",
        "ЗАВАНТАЖЕННЯ ДАНИХ ЗОНИ..."
    )
    var visible by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        for (i in lines.indices) {
            kotlinx.coroutines.delay(120)
            visible = i + 1
        }
    }
    val cursorOn by produceState(initialValue = true) {
        while (true) {
            kotlinx.coroutines.delay(500)
            value = !value
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(PdaColors.ScreenBg).scanlineOverlay(),
        contentAlignment = Alignment.Center
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.take(visible).forEach { line ->
                Text(
                    text = line,
                    style = PdaType.Terminal.copy(
                        color = if (line.startsWith("УВАГА")) PdaColors.Amber else PdaColors.Green,
                        fontSize = 12.sp
                    )
                )
            }
            if (cursorOn) Text("_", style = PdaType.Terminal.copy(color = PdaColors.Green))
        }
    }
}
