package app.voice.dictate.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.voice.dictate.VoiceApp
import app.voice.dictate.asr.ModelCatalog
import app.voice.dictate.asr.ModelKind
import app.voice.dictate.asr.ModelStatus
import app.voice.dictate.data.HISTORY_FOREVER
import app.voice.dictate.data.HISTORY_MAX_DAYS
import app.voice.dictate.data.HISTORY_NEVER
import app.voice.dictate.data.InsertMethod
import app.voice.dictate.data.MODEL_UNLOAD_MAX_SEC
import app.voice.dictate.data.MODEL_UNLOAD_MIN_SEC
import app.voice.dictate.data.TTS_SPEEDS
import app.voice.dictate.data.ThemeMode
import app.voice.dictate.data.formatDuration
import app.voice.dictate.data.historyLabel
import app.voice.dictate.ui.SystemLinks
import app.voice.dictate.ui.components.Caps
import app.voice.dictate.ui.components.ConfirmDialog
import app.voice.dictate.ui.components.SegmentedChoice
import app.voice.dictate.ui.components.TopBar
import app.voice.dictate.ui.components.VoiceCard
import app.voice.dictate.ui.theme.Voice
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(goModels: (ModelKind) -> Unit) {
    val context = LocalContext.current
    val app = VoiceApp.instance
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val repo = app.settings
    val scope = rememberCoroutineScope()
    val model = ModelCatalog.byId(s.modelId)
    var langOpen by remember { mutableStateOf(false) }
    var voiceOpen by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    /** A History choice that would delete saved dictations, and how many: waits for confirmation. */
    var pendingHistory by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var unloadOpen by remember { mutableStateOf(false) }
    val modelStates by app.models.states.collectAsStateWithLifecycle()
    val ttsModel = ModelCatalog.voiceModelById(s.ttsModelId)
    val ttsReady = ttsModel != null && (!ttsModel.needsDownload || modelStates[ttsModel.id]?.status == ModelStatus.READY)
    val ttsVoice = ttsModel?.voice(s.ttsVoice)

    Column(Modifier.fillMaxSize().background(Voice.colors.paper)) {
        TopBar("Settings")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Section("Appearance") {
                SegmentedChoice(
                    listOf("Light" to ThemeMode.LIGHT, "Dark" to ThemeMode.DARK, "System" to ThemeMode.SYSTEM),
                    s.theme, repo::setTheme,
                )
            }

            Section("Speech") {
                Item("Model", model?.languages, value = model?.name, onClick = { goModels(ModelKind.SPEECH) })
                Divider()
                Item(
                    "Language",
                    if (model?.multilingual == true || model?.id == ModelCatalog.SYSTEM_ID) "Used by ${model.name}"
                    else "Nemotron 3.5 and Android built-in are multilingual; the other models are English",
                    value = ModelCatalog.languageLabel(s.language),
                    onClick = { langOpen = true },
                )
            }

            Section("Read aloud") {
                Item(
                    "Voice model",
                    if (ttsReady) ttsModel?.languages else "Not downloaded yet: the phone's voice reads until it is",
                    value = ttsModel?.name,
                    onClick = { goModels(ModelKind.VOICE) },
                )
                Divider()
                if (ttsModel?.voices?.isNotEmpty() == true) {
                    Item("Voice", ttsModel.name, value = ttsVoice?.label, onClick = { voiceOpen = true })
                } else {
                    Item("Voice", "Choose the phone's voice in Android's text-to-speech settings", onClick = { SystemLinks.openTtsSettings(context) })
                }
                Divider()
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text("Speed", style = MaterialTheme.typography.titleSmall, color = Voice.colors.ink)
                    Spacer(Modifier.height(8.dp))
                    SegmentedChoice(TTS_SPEEDS.map { "${it}×".replace(".0×", "×") to it }, s.ttsSpeed, repo::setTtsSpeed)
                }
                Divider()
                Item("Hear this voice", "Plays a short sentence", onClick = { app.readAloud.hearVoice() })
            }

            Section("Memory") {
                Toggle(
                    "Unload models when idle",
                    "Frees memory when the speech or read-aloud model isn't being used. It loads again when you tap a text field.",
                    s.modelUnloadEnabled, repo::setModelUnloadEnabled,
                )
                if (s.modelUnloadEnabled) {
                    Divider()
                    Item("Unload after", "Time since the model was last used", value = formatDuration(s.modelUnloadSec), onClick = { unloadOpen = true })
                }
            }

            Section("Bubble") {
                Toggle("Show the bubble", "The small glass bubble for dictation and read aloud", s.bubbleEnabled, repo::setBubbleEnabled)
                BubbleStatusLine()
                Divider()
                BubbleLookEditor(s, repo)
                Divider()
                Toggle("Only while typing", "Appears when the keyboard opens and hides when it closes", s.bubbleOnlyWhenTyping, repo::setBubbleOnlyWhenTyping)
                Divider()
                Toggle("Hide in full-screen apps", "Stays out of videos, games and photos", s.bubbleHideFullscreen, repo::setBubbleHideFullscreen)
                Divider()
                Toggle("Read aloud in the bubble", "Adds a speaker button next to the mic when you tap the bubble", s.speakerButton, repo::setSpeakerButton)
                Divider()
                Toggle("Vibrate", "A tap when recording starts and stops", s.haptics, repo::setHaptics)
                Divider()
                Item(
                    "Reset position",
                    if (s.bubbleTucked) "The bubble is tucked into the edge. Bring it back to the right edge" else "Back to the right edge, just above the keyboard",
                    onClick = { repo.resetBubble() },
                )
                Divider()
                Text(
                    "Tap the bubble for the mic and speaker buttons. Hold it to talk right away, then let go to type. " +
                        "Tap the mic for hands-free; tap the bubble again to finish. Drag the bubble to move it, or push it " +
                        "past the edge to tuck it away; tap the thin tab to bring it back.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }

            Section("Text") {
                Toggle("Smart spacing and capitals", "Adds a space between words and capitalizes new sentences", s.smartSpacing, repo::setSmartSpacing)
                Divider()
                Toggle("Remove filler words", "Drops \"um\", \"uh\", \"erm\" and \"hmm\"", s.removeFillers, repo::setRemoveFillers)
                Divider()
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text("How text goes in", style = MaterialTheme.typography.titleSmall, color = Voice.colors.ink)
                    Spacer(Modifier.height(8.dp))
                    SegmentedChoice(listOf("Type it" to InsertMethod.AUTO, "Paste it" to InsertMethod.PASTE), s.insertMethod, repo::setInsertMethod)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "\"Type it\" works in most apps. Choose \"Paste it\" if an app ignores typed text (it uses the clipboard).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Section("History") {
                Item(
                    "Keep history",
                    when {
                        s.historyDays < 0 -> "Your last 200 dictations are kept"
                        s.historyDays == HISTORY_NEVER -> "Dictations aren't saved"
                        else -> "Dictations older than ${historyLabel(s.historyDays)} are deleted"
                    },
                    value = historyLabel(s.historyDays),
                    onClick = { historyOpen = true },
                )
            }

            Section("App") {
                Item("Accessibility settings", "Turn the Voice bubble service on or off", onClick = { SystemLinks.openAccessibility(context) })
                Divider()
                Item("App info", "Permissions, and \"Allow restricted settings\"", onClick = { SystemLinks.openAppInfo(context) })
                Divider()
                Item("Delete all models", "Frees up the storage they use", danger = true, onClick = { confirmDeleteAll = true })
            }

            Section("About") {
                Expandable("How it works") {
                    Text(
                        "On your phone. With a downloaded model, your voice goes from the mic into a speech model running on this phone (ONNX Runtime on the CPU). No audio or text is ever uploaded. Android built-in uses your phone's own recognizer instead.\n\n" +
                            "Live models (Nemotron) listen in 0.56-second steps and add words as they hear them. Near-live models (Parakeet, Moonshine) re-read what you just said every moment and settle the text when you pause.\n\n" +
                            "The bubble is an accessibility service: it reads the text field you're in only to put your words at the cursor.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Divider()
                Expandable("Do I have to download again?") {
                    Text(
                        "No. A model downloads once and stays in Voice's private storage. Closing the app, restarting the phone or going offline doesn't remove it. It is removed only if you delete it here or uninstall Voice.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (langOpen) {
        AlertDialog(
            onDismissRequest = { langOpen = false },
            title = { Text("Language", style = MaterialTheme.typography.titleLarge) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(ModelCatalog.LANGUAGES) { l ->
                        Row(
                            Modifier.fillMaxWidth().clickable { repo.setLanguage(l.value); langOpen = false }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = s.language == l.value,
                                onClick = { repo.setLanguage(l.value); langOpen = false },
                                colors = RadioButtonDefaults.colors(selectedColor = Voice.colors.turmeric),
                            )
                            Text(l.label, color = Voice.colors.ink)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { langOpen = false }) { Text("Close", color = Voice.colors.ink) } },
            containerColor = Voice.colors.card,
        )
    }
    if (voiceOpen && ttsModel != null) {
        AlertDialog(
            onDismissRequest = { voiceOpen = false },
            title = { Text("Voice", style = MaterialTheme.typography.titleLarge) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(ttsModel.voices) { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable { repo.setTtsVoice(v.id); voiceOpen = false }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = ttsVoice?.id == v.id,
                                onClick = { repo.setTtsVoice(v.id); voiceOpen = false },
                                colors = RadioButtonDefaults.colors(selectedColor = Voice.colors.turmeric),
                            )
                            Column {
                                Text(v.label, color = Voice.colors.ink)
                                Text(listOf("${v.accent} English", v.gender).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { voiceOpen = false }) { Text("Close", color = Voice.colors.ink) } },
            containerColor = Voice.colors.card,
        )
    }
    if (historyOpen) {
        HistoryDialog(
            current = s.historyDays,
            onPick = { days ->
                historyOpen = false
                val removed = app.history.countRemovedBy(days)
                if (removed > 0) pendingHistory = days to removed else repo.setHistoryDays(days)
            },
            onDismiss = { historyOpen = false },
        )
    }
    pendingHistory?.let { (days, removed) ->
        val items = if (removed == 1) "1 saved dictation" else "$removed saved dictations"
        ConfirmDialog(
            if (days == HISTORY_NEVER) "Stop saving history? This deletes the $items you have now."
            else "Keep history for ${historyLabel(days)}? This deletes $items older than that.",
            "Delete",
            onConfirm = { repo.setHistoryDays(days) },
            onDismiss = { pendingHistory = null },
        )
    }
    if (unloadOpen) {
        UnloadAfterDialog(current = s.modelUnloadSec, onSave = repo::setModelUnloadSec, onDismiss = { unloadOpen = false })
    }
    if (confirmDeleteAll) {
        ConfirmDialog(
            "Delete every downloaded model from this phone? Your transcript and history are kept.",
            "Delete all",
            onConfirm = {
                app.selectModel(ModelCatalog.SYSTEM_ID)
                app.readAloud.unload()
                repo.setTtsModelId(ModelCatalog.SYSTEM_TTS_ID)
                scope.launch { app.models.deleteAll() }
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Caps(title, Modifier.padding(start = 4.dp, bottom = 8.dp))
        VoiceCard(Modifier.fillMaxWidth(), radius = 14.dp) { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = Voice.colors.line)

@Composable
private fun Item(title: String, description: String? = null, value: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val c = Voice.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (danger) c.chilli else c.ink)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        if (value != null) Text(value, fontSize = 13.sp, color = c.ink2, modifier = Modifier.padding(start = 8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.ink3)
    }
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Voice.colors
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.turmeric, checkedThumbColor = Color.White,
                uncheckedTrackColor = c.paperDeep, uncheckedBorderColor = c.line, uncheckedThumbColor = c.ink3,
            ),
        )
    }
}

@Composable
private fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Voice.colors.ink, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null, tint = Voice.colors.ink3, modifier = Modifier.size(22.dp),
            )
        }
        AnimatedVisibility(open) { Column(Modifier.padding(bottom = 12.dp)) { content() } }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = Voice.colors.turmeric))
        Text(label, color = Voice.colors.ink)
    }
}

private val HISTORY_PRESETS = listOf(HISTORY_FOREVER, 1, 7, 30, 90)

/** How long History keeps dictations: a preset, a custom number of days, or not at all. */
@Composable
private fun HistoryDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Voice.colors
    var custom by remember { mutableStateOf(current > 0 && current !in HISTORY_PRESETS) }
    var customText by remember { mutableStateOf(if (current > 0) current.toString() else "") }
    val customDays = customText.toIntOrNull()?.takeIf { it in 1..HISTORY_MAX_DAYS }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep history", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HISTORY_PRESETS.forEach { d -> RadioRow(historyLabel(d), selected = !custom && current == d) { onPick(d) } }
                RadioRow("Custom…", selected = custom) { custom = true }
                if (custom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { v -> customText = v.filter(Char::isDigit).take(4) },
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp),
                        label = { Text("Number of days") },
                        singleLine = true,
                        isError = customText.isNotEmpty() && customDays == null,
                        supportingText = { Text("1 to $HISTORY_MAX_DAYS days") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.turmeric, focusedLabelColor = c.ink2, cursorColor = c.ink),
                    )
                }
                RadioRow("Don't save history", selected = !custom && current == HISTORY_NEVER) { onPick(HISTORY_NEVER) }
            }
        },
        confirmButton = {
            if (custom) TextButton(onClick = { customDays?.let(onPick) }, enabled = customDays != null) { Text("Save", color = if (customDays != null) c.ink else c.ink3) }
            else TextButton(onClick = onDismiss) { Text("Close", color = c.ink) }
        },
        dismissButton = { if (custom) TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink) } },
        containerColor = c.card,
    )
}

/** How long an unused model stays in memory: minutes and seconds, from 5 seconds to 60 minutes. */
@Composable
private fun UnloadAfterDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = Voice.colors
    var minutes by remember { mutableStateOf((current / 60).toString()) }
    var seconds by remember { mutableStateOf((current % 60).toString()) }
    val m = if (minutes.isEmpty()) 0 else minutes.toIntOrNull() ?: -1
    val sec = if (seconds.isEmpty()) 0 else seconds.toIntOrNull() ?: -1
    val total = m * 60 + sec
    val valid = m in 0..60 && sec in 0..59 && total in MODEL_UNLOAD_MIN_SEC..MODEL_UNLOAD_MAX_SEC
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.turmeric, focusedLabelColor = c.ink2, cursorColor = c.ink)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unload after", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text("How long a model stays in memory after you last used it.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { v -> minutes = v.filter(Char::isDigit).take(2) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Minutes") },
                        singleLine = true,
                        isError = m !in 0..60,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fieldColors,
                    )
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { v -> seconds = v.filter(Char::isDigit).take(2) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Seconds") },
                        singleLine = true,
                        isError = sec !in 0..59,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fieldColors,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 30, 60, 300).forEach { preset ->
                        AssistChip(
                            onClick = { minutes = (preset / 60).toString(); seconds = (preset % 60).toString() },
                            label = { Text(formatDuration(preset)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (valid && total == preset) c.turmericSoft else c.card,
                                labelColor = c.ink,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (valid) "Unloads ${formatDuration(total)} after last use"
                    else "Choose between $MODEL_UNLOAD_MIN_SEC seconds and ${MODEL_UNLOAD_MAX_SEC / 60} minutes",
                    fontSize = 13.sp,
                    color = if (valid) c.ink2 else c.chilli,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(total); onDismiss() }, enabled = valid) { Text("Save", color = if (valid) c.ink else c.ink3) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink) } },
        containerColor = c.card,
    )
}
