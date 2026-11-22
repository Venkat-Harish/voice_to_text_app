package app.voice.dictate.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.voice.dictate.ui.theme.Voice
import android.graphics.Color as AndroidColor

/** "#RRGGBB" for an ARGB colour. */
fun hexOf(color: Int): String = String.format(java.util.Locale.US, "#%06X", color and 0xFFFFFF)

/** Parses "#RRGGBB" or "RRGGBB" into an opaque ARGB colour, or null. */
fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (!Regex("^[0-9a-fA-F]{6}$").matches(t)) return null
    return (0xFF000000.toInt()) or t.toInt(16)
}

/**
 * Custom bubble colour: drag in the square for richness and brightness, along the bar for the hue, or type a
 * hex code. The bubble's glass opacity still applies on top.
 */
@Composable
fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Voice.colors
    val start = remember { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var bright by remember { mutableFloatStateOf(start[2]) }
    val color = AndroidColor.HSVToColor(floatArrayOf(hue, sat, bright))
    var hex by remember { mutableStateOf(hexOf(initial)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom colour", style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SaturationBrightnessBox(hue, sat, bright) { s, v ->
                    sat = s; bright = v
                    hex = hexOf(AndroidColor.HSVToColor(floatArrayOf(hue, s, v)))
                }
                HueBar(hue) { h ->
                    hue = h
                    hex = hexOf(AndroidColor.HSVToColor(floatArrayOf(h, sat, bright)))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(color)).border(1.dp, c.line, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            // The same text is shown, parsed and saved.
                            val v = t.trim().take(7)
                            hex = v
                            parseHex(v)?.let { parsed ->
                                val hsv = FloatArray(3)
                                AndroidColor.colorToHSV(parsed, hsv)
                                hue = hsv[0]; sat = hsv[1]; bright = hsv[2]
                            }
                        },
                        singleLine = true,
                        label = { Text("Hex") },
                        modifier = Modifier.width(150.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.line),
                    )
                }
            }
        },
        confirmButton = {
            val chosen = parseHex(hex)
            TextButton(enabled = chosen != null, onClick = { onPick(chosen ?: color); onDismiss() }) {
                Text("Use colour", color = if (chosen != null) c.ink else c.ink3)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink2) } },
        containerColor = c.card,
    )
}

/** Horizontal = richness (saturation), vertical = brightness, for the current hue. */
@Composable
private fun SaturationBrightnessBox(hue: Float, sat: Float, bright: Float, onChange: (Float, Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                fun update(p: Offset) = latest(
                    (p.x / size.width).coerceIn(0f, 1f),
                    1f - (p.y / size.height).coerceIn(0f, 1f),
                )
                awaitEachGesture {
                    val down = awaitFirstDown()
                    update(down.position)
                    down.consume()
                    drag(down.id) { change -> update(change.position); change.consume() }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val at = Offset(sat * size.width, (1f - bright) * size.height)
        drawCircle(Color.Black.copy(alpha = 0.45f), radius = 10.dp.toPx(), center = at, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White, radius = 8.5.dp.toPx(), center = at, style = Stroke(2.dp.toPx()))
    }
}

/** The colour wheel unrolled into a bar. */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    val rainbow = remember { (0..6).map { Color(AndroidColor.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f))) } }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .pointerInput(Unit) {
                fun update(p: Offset) = latest((p.x / size.width).coerceIn(0f, 1f) * 359.9f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    update(down.position)
                    down.consume()
                    drag(down.id) { change -> update(change.position); change.consume() }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(rainbow))
        val r = size.height / 2 - 2.dp.toPx()
        val x = (hue / 360f * size.width).coerceIn(r + 2.dp.toPx(), size.width - r - 2.dp.toPx())
        drawCircle(Color.White, radius = r, center = Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
    }
}
