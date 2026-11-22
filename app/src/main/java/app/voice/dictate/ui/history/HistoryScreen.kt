package app.voice.dictate.ui.history

import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.voice.dictate.VoiceApp
import app.voice.dictate.data.HISTORY_NEVER
import app.voice.dictate.data.HistoryItem
import app.voice.dictate.data.historyLabel
import app.voice.dictate.ui.SystemLinks
import app.voice.dictate.ui.components.ConfirmDialog
import app.voice.dictate.ui.components.TopBar
import app.voice.dictate.ui.components.VoiceCard
import app.voice.dictate.ui.theme.Serif
import app.voice.dictate.ui.theme.Voice

@Composable
fun HistoryScreen() {
    val app = VoiceApp.instance
    val items by app.history.items.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<HistoryItem?>(null) }
    // Drop anything that expired while the app was open.
    LaunchedEffect(Unit) { app.history.prune() }
    val days = settings.historyDays

    Column(Modifier.fillMaxSize().background(Voice.colors.paper)) {
        TopBar("History") {
            if (items.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear", color = Voice.colors.chilli) }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (days == HISTORY_NEVER) "History is off." else "Nothing yet.",
                        fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 22.sp, color = Voice.colors.ink2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (days == HISTORY_NEVER) "Dictations aren't saved. Turn history on in Settings."
                        else "Everything you dictate with the bubble appears here, so you can copy it again.",
                        fontSize = 13.sp, color = Voice.colors.ink3,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { item -> HistoryRow(item, onDelete = { deleting = item }) }
                item {
                    Text(
                        "Tap to copy · long-press to delete. " +
                            if (days > 0) "Kept on this phone for ${historyLabel(days)}." else "Kept on this phone only.",
                        fontSize = 12.sp, color = Voice.colors.ink3,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog("Clear your whole dictation history?", "Clear", onConfirm = { app.history.clear() }, onDismiss = { confirmClear = false })
    }
    deleting?.let { item ->
        ConfirmDialog("Delete this dictation?", "Delete", onConfirm = { app.history.delete(item.id) }, onDismiss = { deleting = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(item: HistoryItem, onDelete: () -> Unit) {
    val context = LocalContext.current
    val c = Voice.colors
    val where = remember(item.app) { appLabel(context, item.app) }
    VoiceCard(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { SystemLinks.copy(context, item.text) }, onLongClick = onDelete),
        radius = 14.dp,
    ) {
        Text(item.text, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        val result = when (item.result) { "typed" -> "typed"; "pasted" -> "pasted"; else -> "copied" }
        Text(
            listOfNotNull(
                DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                where?.let { "$result into $it" } ?: result,
            ).joinToString(" · "),
            fontSize = 12.sp, color = c.ink3,
        )
    }
}

private fun appLabel(context: Context, pkg: String?): String? {
    if (pkg.isNullOrBlank()) return null
    if (pkg == context.packageName) return "Voice"
    return try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() }
    }
}
