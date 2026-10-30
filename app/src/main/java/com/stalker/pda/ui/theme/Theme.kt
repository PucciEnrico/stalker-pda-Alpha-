package com.stalker.pda.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.stalker.pda.R

object PdaColors {
    // screen
    val ScreenBg = Color(0xFF0B0D12)
    val PanelBg = Color(0xFF10131A)
    val PanelAlt = Color(0xFF161A23)
    val HeaderBar = Color(0xFF3F4152)
    val HeaderBarDark = Color(0xFF2B2D3A)
    val Border = Color(0xFF2B2E39)
    val BorderBright = Color(0xFF4A4E5C)
    val SelectBar = Color(0xFF3A3F55)

    // text
    val TextWhite = Color(0xFFE9E9EC)
    val TextGrey = Color(0xFF9496A0)
    val TextDim = Color(0xFF5D606B)

    // accents
    val Green = Color(0xFF3DFF3D)
    val GreenDim = Color(0xFF1FA01F)
    val Amber = Color(0xFFF2B632)
    val Red = Color(0xFFE23B2E)
    val Cyan = Color(0xFF3FE0D0)
    val Orange = Color(0xFFFF8A1F)
    val Magenta = Color(0xFFFF3DCB)

    // device body
    val MetalLight = Color(0xFFB9BBBE)
    val MetalMid = Color(0xFF8E9195)
    val MetalDark = Color(0xFF4D4F53)
    val MetalEdge = Color(0xFF26272A)
}

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_bold, FontWeight.Bold),
    Font(R.font.jetbrainsmono_italic, FontWeight.Normal, FontStyle.Italic)
)

object PdaType {
    val Title = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = PdaColors.TextWhite)
    val Display = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PdaColors.TextWhite)
    val Body = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 10.sp, color = PdaColors.TextWhite)
    val Small = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 9.sp, color = PdaColors.TextGrey)
    val Tiny = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 8.sp, color = PdaColors.TextGrey)
    val Tab = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = PdaColors.TextGrey)
    val Terminal = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 15.sp, color = PdaColors.Green)
}

private val PdaDarkColorScheme = darkColorScheme(
    primary = PdaColors.Green,
    onPrimary = PdaColors.ScreenBg,
    secondary = PdaColors.Amber,
    background = PdaColors.ScreenBg,
    surface = PdaColors.PanelBg,
    onSurface = PdaColors.TextWhite,
    onBackground = PdaColors.TextWhite,
    error = PdaColors.Red,
    outline = PdaColors.Border
)

@Composable
fun StalkerPdaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PdaDarkColorScheme, content = content)
}
