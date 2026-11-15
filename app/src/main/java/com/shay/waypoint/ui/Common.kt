package com.shay.waypoint.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shay.waypoint.data.*
import java.time.*

val Black = Color(0xFF000000)
val S1 = Color(0xFF0D0D0D)
val S2 = Color(0xFF1A1A1A)
val LineC = Color(0xFF242424)
val TextC = Color(0xFFF2F2F2)
val Mute = Color(0xFF8A8A8A)
val RedC = Color(0xFFFF5A5A)
val AmberC = Color(0xFFFFB547)
val GreenC = Color(0xFF3DD68C)

fun hexColor(s: String, def: Color = Color(0xFF2F80FF)): Color = try {
    val t = s.trim().removePrefix("#")
    if (t.length == 6) Color(0xFF000000 or t.toLong(16)) else def
} catch (_: Exception) { def }

@Composable
fun WaypointTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent, onPrimary = Color.White, primaryContainer = accent.copy(alpha = 0.18f), onPrimaryContainer = accent,
        secondary = accent, onSecondary = Color.White, secondaryContainer = accent.copy(alpha = 0.18f), onSecondaryContainer = accent,
        tertiary = accent, background = Black, onBackground = TextC, surface = Black, onSurface = TextC,
        surfaceVariant = S2, onSurfaceVariant = Mute, outline = LineC, outlineVariant = LineC,
        surfaceContainerLowest = Black, surfaceContainerLow = S1, surfaceContainer = S1,
        surfaceContainerHigh = S2, surfaceContainerHighest = S2, error = RedC,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------- "trail log" design language ----------------
// Diamond waypoint markers, cut-corner tag cards, monospace instrument numerals, segmented gauges.

val Mono = FontFamily.Monospace
val CardShape = CutCornerShape(topEnd = 18.dp)
val TagShape = CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp)
val DiamondShape = CutCornerShape(50)

/** A small diamond marker (a rotated square). */
@Composable
fun Diamond(size: Dp, fill: Color, border: Color = fill, borderWidth: Dp = 0.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(DiamondShape).background(fill)
            .let { if (borderWidth > 0.dp) it.border(borderWidth, border, DiamondShape) else it },
    )
}

@Composable
fun WCard(modifier: Modifier = Modifier, label: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(CardShape)
            .background(S1)
            .border(1.dp, LineC, CardShape)
            .drawBehind {
                // accent band along the inside of the cut corner
                val c = 18.dp.toPx(); val t = 3.dp.toPx()
                val band = Path().apply {
                    moveTo(size.width - c, 0f); lineTo(size.width, c)
                    lineTo(size.width, c + t * 1.4f); lineTo(size.width - c - t * 1.4f, 0f); close()
                }
                drawPath(band, accent)
            }
            .padding(14.dp),
    ) {
        if (label != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 10.dp)) {
                if (label != null) SectionLabel(label, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
        }
        content()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Diamond(6.dp, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = Mute, fontSize = 11.sp, letterSpacing = 1.6.sp, fontFamily = Mono, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text.uppercase(), fontSize = 10.sp, color = color, maxLines = 1, fontFamily = Mono, letterSpacing = 0.8.sp,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(TagShape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.35f), TagShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Tick-off control: a diamond waypoint. Filled = done; lock glyph = chained step not yet reachable. */
@Composable
fun CheckDot(done: Boolean, locked: Boolean = false, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.size(44.dp).clickable(enabled = !locked, onClick = onClick), contentAlignment = Alignment.Center) {
        Diamond(22.dp, if (done) accent else Color.Transparent, if (done) accent else Color(0xFF4A4A4A), 2.dp)
        if (done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
        if (locked) Icon(Icons.Filled.Lock, null, tint = Color(0xFF555555), modifier = Modifier.size(11.dp))
    }
}

@Composable
fun ListRow(
    title: String, sub: String? = null, done: Boolean = false, muted: Boolean = false,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null, divider: Boolean = true,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f).padding(start = if (leading == null) 0.dp else 4.dp, end = 8.dp)) {
                Text(
                    title, fontSize = 15.sp, color = if (done || muted) Mute else TextC, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                if (!sub.isNullOrEmpty()) Text(sub, fontSize = 12.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
        if (divider) HorizontalDivider(color = LineC, thickness = 1.dp)
    }
}

/** Segmented gauge, like a ruler: 24 ticks, filled up to [frac]. */
@Composable
fun ProgressBar(frac: Float, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    val f = if (frac.isNaN()) 0f else frac.coerceIn(0f, 1f)
    val n = 24
    val filled = Math.round(f * n)
    Row(modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until n) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(CutCornerShape(topEnd = 2.dp)).background(if (i < filled) color else S2))
        }
    }
}

@Composable
fun BigNumber(main: String, sub: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(main, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, letterSpacing = (-1).sp)
        Text("  $sub", fontSize = 13.sp, color = Mute, fontFamily = Mono, modifier = Modifier.padding(bottom = 5.dp))
    }
}

/** Faint topographic contour lines, drawn behind the header. */
@Composable
fun TopoBackdrop(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (k in 0 until 7) {
            val path = Path()
            val base = h * (0.15f + k * 0.13f)
            val amp = h * (0.06f + 0.02f * (k % 3))
            var x = 0f
            path.moveTo(0f, base)
            while (x <= w + 8f) {
                val y = base + amp * kotlin.math.sin(x / w * 6.3f + k * 0.9f) + amp * 0.5f * kotlin.math.sin(x / w * 13f + k * 2.1f)
                path.lineTo(x, y); x += 8f
            }
            drawPath(path, accent.copy(alpha = if (k == 3) 0.22f else 0.08f), style = Stroke(width = 1.2.dp.toPx()))
        }
    }
}

@Composable
fun MutedText(s: String, modifier: Modifier = Modifier) = Text(s, color = Mute, fontSize = 13.sp, modifier = modifier)

// ---------------- dialogs ----------------

@Composable
fun TextDialog(
    title: String, initial: String, onDismiss: () -> Unit, number: Boolean = false, hint: String = "",
    multiline: Boolean = false, onDelete: (() -> Unit)? = null, onSave: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = S2,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                v, { v = it }, placeholder = { Text(hint) }, singleLine = !multiline, minLines = if (multiline) 3 else 1,
                keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton({ onSave(v.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton({ onDelete(); onDismiss() }) { Text("Delete", color = RedC) }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(title: String, initial: LocalTime?, onDismiss: () -> Unit, onClear: (() -> Unit)? = null, onSave: (LocalTime) -> Unit) {
    val st = rememberTimePickerState(initial?.hour ?: 9, initial?.minute ?: 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = S2,
        title = { Text(title) },
        text = { TimePicker(st) },
        confirmButton = { TextButton({ onSave(LocalTime.of(st.hour, st.minute)); onDismiss() }) { Text("OK") } },
        dismissButton = {
            Row {
                if (onClear != null) TextButton({ onClear(); onDismiss() }) { Text("Clear") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(initial: LocalDate?, onDismiss: () -> Unit, onSave: (LocalDate) -> Unit) {
    val st = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                st.selectedDateMillis?.let { onSave(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        colors = DatePickerDefaults.colors(containerColor = S2),
    ) { DatePicker(st) }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "OK", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = S2,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton({ onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SwatchRow(colours: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        colours.forEach { c ->
            Box(
                Modifier.size(38.dp).clip(DiamondShape).background(hexColor(c))
                    .border(2.dp, if (c.equals(selected, true)) Color.White else Color.Transparent, DiamondShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

fun priorityColor(p: String): Color? = when (p) { "high" -> RedC; "low" -> Mute; else -> null }
