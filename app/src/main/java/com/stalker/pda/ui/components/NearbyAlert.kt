package com.stalker.pda.ui.components

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.stalker.pda.R
import com.stalker.pda.data.model.Contact
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType

/** Pop-up (+ beep_pda.wav) when a friend comes within 100 m. */
@Composable
fun NearbyContactAlert(contact: Contact, onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(contact.id) { playSound(context, R.raw.beep_pda) }

    val transition = rememberInfiniteTransition(label = "radar")
    val ring by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "ring"
    )

    Popup(alignment = Alignment.TopEnd, properties = PopupProperties(focusable = false)) {
        Box(
            modifier = Modifier
                .width(230.dp)
                .padding(top = 30.dp, end = 8.dp)
                .background(PdaColors.PanelBg)
                .border(1.dp, PdaColors.Amber.copy(alpha = 0.8f))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("◉ ЛОКАЛЬНА МЕРЕЖА", style = PdaType.Small.copy(color = PdaColors.Amber))
                    Canvas(modifier = Modifier.size(22.dp)) {
                        val c = Offset(size.width / 2, size.height / 2)
                        drawCircle(
                            color = PdaColors.Amber.copy(alpha = 1f - ring),
                            radius = size.minDimension / 2 * ring,
                            center = c,
                            style = Stroke(width = 1.5f)
                        )
                        drawCircle(PdaColors.Amber, 3f, c)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(contact.callsign, style = PdaType.Display.copy(color = PdaColors.Amber, fontSize = 14.sp))
                Text("Друг поруч: менше 100 м", style = PdaType.Small)
                Spacer(Modifier.height(8.dp))
                PdaButton("[ ПРИЙНЯТО ]", PdaColors.Amber, onDismiss, Modifier.fillMaxWidth())
            }
        }
    }
}
