package com.stalker.pda.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stalker.pda.R
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import kotlin.math.absoluteValue

/** Grey-violet title bar of a panel ("Завдання", "Журнал", ...). */
@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .background(Brush.horizontalGradient(listOf(PdaColors.HeaderBar, PdaColors.HeaderBarDark)))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = PdaType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Thin bar with a path like "/ Особисті нотатки / Перші враження". */
@Composable
fun BreadcrumbBar(path: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Brush.horizontalGradient(listOf(PdaColors.HeaderBar, PdaColors.HeaderBarDark)))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(path, style = PdaType.Small.copy(color = PdaColors.TextWhite), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier.background(PdaColors.PanelBg).border(1.dp, PdaColors.Border), content = content)
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = PdaType.Small.copy(color = PdaColors.TextDim))
    }
}

/** "+Категорія" / "-Категорія" green tree node. */
@Composable
fun TreeCategoryRow(label: String, expanded: Boolean, depth: Int = 0, onClick: () -> Unit) {
    Text(
        text = (if (expanded) "-" else "+") + label,
        style = PdaType.Body.copy(color = PdaColors.Green),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (6 + depth * 10).dp, end = 6.dp, top = 3.dp, bottom = 3.dp)
    )
}

@Composable
fun TreeLeafRow(label: String, selected: Boolean, depth: Int = 0, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) PdaColors.SelectBar else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = (18 + depth * 10).dp, end = 6.dp, top = 3.dp, bottom = 3.dp)
    ) {
        Text(
            text = label,
            style = PdaType.Body.copy(color = if (selected) PdaColors.TextWhite else PdaColors.Green),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Placeholder portrait (silhouette). Replace with real avatars later. */
@Composable
fun PortraitBox(seed: String, modifier: Modifier = Modifier) {
    val hue = (seed.hashCode().absoluteValue % 360).toFloat()
    val tint = Color.hsv(hue, 0.35f, 0.6f)
    Canvas(modifier = modifier.clipToBounds().border(1.dp, PdaColors.Border)) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF2C313D), Color(0xFF12151C))))
        val cx = size.width / 2f
        drawCircle(tint, radius = size.minDimension * 0.2f, center = Offset(cx, size.height * 0.36f))
        drawCircle(tint.copy(alpha = 0.85f), radius = size.minDimension * 0.4f, center = Offset(cx, size.height * 1.02f))
    }
}

@Composable
fun PdaButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val ctx = LocalContext.current
    val c = if (enabled) color else PdaColors.TextDim
    Box(
        modifier = modifier
            .height(24.dp)
            .background(c.copy(alpha = 0.12f))
            .border(1.dp, c.copy(alpha = 0.7f))
            .clickable(enabled = enabled) {
                playSound(ctx, R.raw.pda_click)
                onClick()
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = PdaType.Small.copy(color = c, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

@Composable
fun PdaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false
) {
    Box(
        modifier = modifier
            .background(PdaColors.PanelAlt)
            .border(1.dp, PdaColors.Border)
            .padding(6.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = PdaType.Body,
            cursorBrush = SolidColor(PdaColors.Green),
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, style = PdaType.Body.copy(color = PdaColors.TextDim))
                inner()
            }
        )
    }
}

@Composable
fun PdaDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(PdaColors.PanelBg)
                .border(1.dp, PdaColors.BorderBright)
        ) {
            PanelHeader(title)
            Column(
                modifier = Modifier.padding(8.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

// ── tiny helpers for "expanded tree nodes" kept in rememberSaveable as one string ──
fun hasKey(set: String, key: String): Boolean = set.split("|").contains(key)

fun toggleKey(set: String, key: String): String {
    val items = set.split("|").filter { it.isNotEmpty() }.toMutableList()
    if (!items.remove(key)) items.add(key)
    return items.joinToString("|")
}

/** Contact / task / own picture if there is one, otherwise a silhouette. */
@Composable
fun AvatarView(seed: String, path: String?, modifier: Modifier = Modifier) {
    if (path != null && java.io.File(path).exists()) {
        PhotoView(path, modifier.border(1.dp, PdaColors.Border), 200)
    } else {
        PortraitBox(seed, modifier)
    }
}

/** Single tap and double tap on the same element. */
fun Modifier.tapAndDouble(onTap: () -> Unit, onDouble: () -> Unit): Modifier =
    this.pointerInput(onTap, onDouble) {
        detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDouble() })
    }

@Composable
fun PhotoDialog(path: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .size(width = 460.dp, height = 300.dp)
                .background(Color.Black)
                .clickable(onClick = onClose)
        ) {
            PhotoView(path, Modifier.fillMaxSize(), 1600)
        }
    }
}
