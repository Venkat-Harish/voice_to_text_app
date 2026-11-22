package app.voice.dictate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voice.dictate.ui.theme.Voice

/** Screen title bar like the prototype's NavBar: serif title, hairline under it. */
@Composable
fun TopBar(title: String, right: @Composable RowScope.() -> Unit = {}) {
    val c = Voice.colors
    Column(Modifier.fillMaxWidth().background(c.paper).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.weight(1f))
            right()
        }
        HorizontalDivider(color = c.line)
    }
}

/** Card with the prototype's warm card colour and hairline border. */
@Composable
fun VoiceCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    radius: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Voice.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        color = c.card,
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) c.turmeric else c.line),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** Small uppercase label ("caps" in the prototype). */
@Composable
fun Caps(text: String, modifier: Modifier = Modifier, color: Color = Voice.colors.ink2) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun Tag(text: String, color: Color = Voice.colors.ink2, fill: Color = Color.Transparent) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Pill showing the selected model, like the prototype's ModelChip. */
@Composable
fun ModelChip(name: String, dot: Color, onClick: () -> Unit) {
    val c = Voice.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, c.line, RoundedCornerShape(50))
            .background(c.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(dot)
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1)
    }
}

/** Highlighted notice like antd-mobile's NoticeBar (turmeric-soft background). */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val c = Voice.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.turmericSoft)
            .border(1.dp, c.turmeric, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = c.ink, modifier = Modifier.weight(1f))
        if (action != null) {
            Spacer(Modifier.size(8.dp))
            Text(action, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.clickable(onClick = onAction))
        }
    }
}

@Composable
fun ConfirmDialog(
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = Voice.colors.ink) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = Voice.colors.chilli) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancel, color = Voice.colors.ink) } },
        containerColor = Voice.colors.card,
    )
}

/** A row of mutually exclusive choices (the prototype's Selector): ink when selected, card otherwise. */
@Composable
fun <T> SegmentedChoice(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Voice.colors
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (label, value) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.ink, activeContentColor = c.paper,
                    inactiveContainerColor = c.card, inactiveContentColor = c.ink,
                    activeBorderColor = c.line, inactiveBorderColor = c.line,
                ),
                icon = {},
            ) { Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}
