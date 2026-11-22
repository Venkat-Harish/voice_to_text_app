package app.voice.dictate.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import android.view.View
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.voice.dictate.data.BUBBLE_SIZE_MAX
import app.voice.dictate.data.BUBBLE_SIZE_MIN
import app.voice.dictate.data.BubblePalette
import app.voice.dictate.data.Settings
import app.voice.dictate.data.SettingsRepository
import app.voice.dictate.dictation.BubbleStatus
import app.voice.dictate.dictation.BubbleView
import app.voice.dictate.dictation.DictationService
import app.voice.dictate.dictation.EdgeTabView
import app.voice.dictate.dictation.GlassStyle
import app.voice.dictate.ui.components.Dot
import app.voice.dictate.ui.theme.Voice
import kotlin.math.roundToInt

/** "Now: showing / hidden until a keyboard opens / …", so you can tell why the bubble isn't on screen. */
@Composable
fun BubbleStatusLine() {
    val status by DictationService.bubbleStatus.collectAsStateWithLifecycle()
    val c = Voice.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Dot(if (status == BubbleStatus.SHOWING) c.leaf else c.ink3)
        Spacer(Modifier.width(6.dp))
        Text("Now: ${status.label}", fontSize = 12.sp, color = c.ink2)
    }
}

/**
 * Size, glass opacity, idle fade and colour of the bubble, with a live preview drawn by the real bubble views.
 * Sliders move the preview as you drag and are saved when you let go.
 */
@Composable
fun BubbleLookEditor(settings: Settings, repo: SettingsRepository) {
    var size by remember(settings.bubbleSizeDp) { mutableFloatStateOf(settings.bubbleSizeDp.toFloat()) }
    var glass by remember(settings.bubbleGlassOpacity) { mutableFloatStateOf(settings.bubbleGlassOpacity) }
    var idle by remember(settings.bubbleIdleOpacity) { mutableFloatStateOf(settings.bubbleIdleOpacity) }
    var pickerOpen by remember { mutableStateOf(false) }
    val style = GlassStyle(settings.bubbleColor, glass)

    Column(Modifier.padding(vertical = 10.dp)) {
        BubblePreview(style, size.roundToInt(), idle)
        Spacer(Modifier.height(14.dp))
        SliderRow(
            title = "Size", value = "${size.roundToInt()} dp",
            current = size, onChange = { size = it },
            range = BUBBLE_SIZE_MIN.toFloat()..BUBBLE_SIZE_MAX.toFloat(), steps = (BUBBLE_SIZE_MAX - BUBBLE_SIZE_MIN) / 2 - 1,
            onDone = { repo.setBubbleSizeDp(size.roundToInt()) },
        )
        SliderRow(
            title = "Glass opacity", value = "${(glass * 100).roundToInt()}%",
            current = glass, onChange = { glass = it },
            range = GlassStyle.MIN_OPACITY..1f, steps = 13,
            onDone = { repo.setBubbleGlassOpacity(glass) },
        )
        SliderRow(
            title = "Idle fade", value = if (idle >= 0.995f) "Never fades" else "${(idle * 100).roundToInt()}%",
            current = idle, onChange = { idle = it },
            range = 0.2f..1f, steps = 15,
            onDone = { repo.setBubbleIdleOpacity(idle) },
        )
        Spacer(Modifier.height(6.dp))
        Text("Colour", style = MaterialTheme.typography.titleSmall, color = Voice.colors.ink)
        Spacer(Modifier.height(10.dp))
        ColorSwatches(settings.bubbleColor, onPick = repo::setBubbleColor, onCustom = { pickerOpen = true })
    }

    if (pickerOpen) {
        ColorPickerDialog(initial = settings.bubbleColor, onPick = repo::setBubbleColor, onDismiss = { pickerOpen = false })
    }
}

/** The bubble in use, the bubble faded, and the tucked tab, over a light-to-dark backdrop. */
@Composable
private fun BubblePreview(style: GlassStyle, sizeDp: Int, idleOpacity: Float) {
    val c = Voice.colors
    val sizePx = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val tabAlpha = maxOf(idleOpacity, GlassStyle.TAB_MIN_FILL)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(124.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFF1ECE3), Color(0xFF9A948C), Color(0xFF1F1C19))))
                .border(1.dp, c.line, RoundedCornerShape(14.dp))
                .clearAndSetSemantics { contentDescription = "Preview of the bubble in use, idle and tucked" },
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { previewOnly(BubbleView(it, sizePx)) },
                        update = { v -> v.diameter = sizePx; v.style = style; v.alpha = 1f },
                    )
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { previewOnly(BubbleView(it, sizePx)) },
                        update = { v -> v.diameter = sizePx; v.style = style; v.alpha = idleOpacity },
                    )
                }
                // The tab hugs the right edge, as it does on the real screen edge.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    AndroidView(
                        factory = { previewOnly(EdgeTabView(it)) },
                        update = { v -> v.style = style; v.onRight = true; v.alpha = tabAlpha },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            for (label in listOf("In use", "Idle", "Tucked")) {
                Text(
                    label, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.weight(1f),
                    textAlign = if (label == "Tucked") androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    current: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onDone: () -> Unit,
) {
    val c = Voice.colors
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.ink2)
        }
        Slider(
            value = current,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            steps = steps,
            // TalkBack reads the real value ("44 dp", "70%") instead of a percentage of the track.
            modifier = Modifier.semantics { contentDescription = title; stateDescription = value },
            colors = SliderDefaults.colors(
                thumbColor = c.ink,
                activeTrackColor = c.ink,
                inactiveTrackColor = c.line,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatches(selected: Int, onPick: (Int) -> Unit, onCustom: () -> Unit) {
    val c = Voice.colors
    val presetName = BubblePalette.nameOf(selected)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BubblePalette.SWATCHES.forEach { sw ->
            Swatch(Color(sw.color), sw.name, selected = presetName == sw.name) { onPick(sw.color) }
        }
        // Custom: a rainbow ring, filled with your colour once you have picked one.
        val custom = presetName == null
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .border(if (custom) 2.dp else 0.dp, if (custom) c.ink else Color.Transparent, CircleShape)
                .clickable(onClick = onCustom)
                .semantics { contentDescription = "Custom colour"; this.selected = custom },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(listOf(Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFE53935)))),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(if (custom) Color(selected) else c.card),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!custom) Icon(Icons.Filled.Add, contentDescription = null, tint = c.ink, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(presetName ?: "Custom ${hexOf(selected)}", fontSize = 12.sp, color = c.ink2)
}

@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    val c = Voice.colors
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .border(if (selected) 2.dp else 0.dp, if (selected) c.ink else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = name; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(color).border(1.dp, c.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                val light = GlassStyle.prefersDarkMarks(colorToArgb(color))
                Icon(Icons.Filled.Check, contentDescription = null, tint = if (light) Color(0xFF1C1C1E) else Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The preview views are pictures only: keep the live bubble's labels away from TalkBack. */
private fun <T : View> previewOnly(view: T): T = view.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

private fun colorToArgb(color: Color): Int =
    ((color.alpha * 255).roundToInt() shl 24) or ((color.red * 255).roundToInt() shl 16) or
        ((color.green * 255).roundToInt() shl 8) or (color.blue * 255).roundToInt()
