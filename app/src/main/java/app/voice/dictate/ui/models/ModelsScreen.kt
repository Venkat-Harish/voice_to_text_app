package app.voice.dictate.ui.models

import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.SpeechRecognizer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.voice.dictate.VoiceApp
import app.voice.dictate.asr.AsrStatus
import app.voice.dictate.asr.ModelCatalog
import app.voice.dictate.asr.ModelKind
import app.voice.dictate.asr.ModelState
import app.voice.dictate.asr.ModelStatus
import app.voice.dictate.asr.SpeechModel
import app.voice.dictate.asr.fmtBytes
import app.voice.dictate.ui.SystemLinks
import app.voice.dictate.ui.components.ConfirmDialog
import app.voice.dictate.ui.components.SegmentedChoice
import app.voice.dictate.ui.components.Tag
import app.voice.dictate.ui.components.TopBar
import app.voice.dictate.ui.components.VoiceCard
import app.voice.dictate.ui.home.InAppDictation
import app.voice.dictate.ui.home.InAppStatus
import app.voice.dictate.tts.TtsStatus
import app.voice.dictate.ui.theme.Mono
import app.voice.dictate.ui.theme.Voice

/** Downloadable models, one kind at a time: voice to text (dictation) or text to voice (read aloud). */
@Composable
fun ModelsScreen(kind: ModelKind, onKind: (ModelKind) -> Unit) {
    val app = VoiceApp.instance
    val c = Voice.colors
    val states by app.models.states.collectAsStateWithLifecycle()
    val storage by app.models.storage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.models.refresh() }
    // Each list keeps its own scroll position when you switch between them.
    val speechList = rememberLazyListState()
    val voiceList = rememberLazyListState()
    val models = if (kind == ModelKind.VOICE) ModelCatalog.VOICE_MODELS else ModelCatalog.MODELS

    Column(Modifier.fillMaxSize().background(c.paper)) {
        TopBar("Models")
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            SegmentedChoice(listOf("Voice to text" to ModelKind.SPEECH, "Text to voice" to ModelKind.VOICE), kind, onKind)
            Spacer(Modifier.height(6.dp))
            Text("Runs on this phone. Download once, works offline.", fontSize = 12.sp, color = c.ink3)
        }
        LazyColumn(
            Modifier.weight(1f),
            state = if (kind == ModelKind.VOICE) voiceList else speechList,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(models, key = { it.id }) { m -> ModelCard(m, states[m.id] ?: ModelState()) }
            item {
                Text(
                    "Models use ${fmtBytes(storage.first)} · ${fmtBytes(storage.second)} free on this phone",
                    fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** One model: name, size, error rate (or voices), where it runs and its action; tap for the details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(model: SpeechModel, state: ModelState) {
    val context = LocalContext.current
    val app = VoiceApp.instance
    val c = Voice.colors
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val asr by app.asr.state.collectAsStateWithLifecycle()
    val inAppStatus by InAppDictation.status.collectAsStateWithLifecycle()
    val tts by app.readAloud.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(model.id) { mutableStateOf(false) }

    // Speech-to-text models and read-aloud voices share this card.
    val voice = model.kind == ModelKind.VOICE
    val selected = if (voice) settings.ttsModelId == model.id else settings.modelId == model.id
    val system = !model.needsDownload
    val status = if (system) ModelStatus.READY else state.status
    val total = model.totalBytes
    val pct = if (total > 0) (state.bytes * 100 / total).toInt() else 0
    val busy = if (voice) tts.reading else inAppStatus != InAppStatus.IDLE
    val inUse = selected && (system || (if (voice) tts.modelId == model.id else asr.modelId == model.id))
    val loading = inUse && (if (voice) tts.status == TtsStatus.LOADING else asr.status == AsrStatus.LOADING)
    val recommended = if (voice) model.recommended else model.id == app.recommendedModelId
    val select = { if (voice) app.settings.setTtsModelId(model.id) else app.selectModel(model.id) }
    val download = { app.models.download(model.id) }
    val systemAvailable = remember {
        if (voice) {
            context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
        } else {
            SpeechRecognizer.isRecognitionAvailable(context) ||
                (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        }
    }
    val systemOnDevice = remember { Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
    val shape = RoundedCornerShape(14.dp)

    VoiceCard(
        Modifier.fillMaxWidth().clip(shape).clickable { expanded = !expanded },
        highlighted = selected,
        radius = 14.dp,
    ) {
        // Name, and the action at the end.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.name, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            when {
                selected && status == ModelStatus.READY -> InUse(loading)
                system -> SmallButton(if (systemAvailable) "Use" else "Unavailable", select, enabled = systemAvailable && !busy)
                status == ModelStatus.DOWNLOADING -> SmallButton("Pause", { app.models.cancel(model.id) }, primary = false)
                status == ModelStatus.READY -> SmallButton("Use", select, enabled = !busy)
                status == ModelStatus.PARTIAL -> SmallButton("Resume", download)
                else -> SmallButton("Download", download, enabled = status != ModelStatus.CHECKING)
            }
        }
        Spacer(Modifier.height(10.dp))
        // The numbers that matter when choosing.
        Row(verticalAlignment = Alignment.Bottom) {
            Stat("Download", if (system) "None" else fmtBytes(total), Modifier.weight(1f))
            if (voice) Stat("Voices", if (system) "Phone's" else "${model.voices.size}", Modifier.weight(1f))
            else Stat("Error rate", model.wer?.let { "$it%" } ?: "—", Modifier.weight(1f))
            Stat("Runs on", model.runsOn, Modifier.weight(1.4f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide details" else "Show details",
                tint = c.ink3, modifier = Modifier.size(20.dp),
            )
        }

        if (status == ModelStatus.DOWNLOADING) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = c.turmeric,
                trackColor = c.paperDeep,
            )
            Text(
                "${fmtBytes(state.bytes)} of ${fmtBytes(total)} · $pct%",
                fontFamily = Mono, fontSize = 11.sp, color = c.ink2, modifier = Modifier.padding(top = 4.dp),
            )
        }
        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 12.sp, color = c.chilli)
        }

        // Details, on tap.
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 12.dp)) {
                HorizontalDivider(color = c.line)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (system) model.vendor else "${model.vendor} · ${model.params} parameters",
                    fontFamily = Mono, fontSize = 12.sp, color = c.ink2,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (recommended) Tag(if (voice) "Recommended" else "Recommended for this phone", color = c.leaf)
                    when {
                        voice && system -> Tag("The phone's own voices", color = c.ink2)
                        voice -> Tag("${model.voices.size} voices", color = c.turmeric)
                        system -> Tag(if (systemOnDevice) "On this phone" else "May use the internet", color = c.ink2)
                        else -> Tag(if (model.live) "Live · word by word" else "Near-live · settles on pause", color = if (model.live) c.turmeric else c.ink2)
                    }
                    Tag(model.languages)
                }
                Spacer(Modifier.height(10.dp))
                Text(model.blurb, style = MaterialTheme.typography.bodyMedium)

                val canHear = voice && selected && status == ModelStatus.READY
                val canDelete = !system && status == ModelStatus.READY
                if (canHear || canDelete) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (canHear) SmallButton(if (loading) "Loading…" else "Hear this voice", { app.readAloud.hearVoice() }, enabled = !busy && !loading, primary = false)
                        if (canDelete) {
                            TextButton(onClick = { confirmDelete = true }, enabled = !(inUse && busy)) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = c.chilli, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Delete from phone", color = c.chilli, fontSize = 13.sp)
                            }
                        }
                    }
                }
                if (model.source != null) {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("${model.license} · ", fontSize = 12.sp, color = c.ink3)
                        Text(
                            "model card",
                            fontSize = 12.sp, color = c.ink2, textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { SystemLinks.openUrl(context, model.source) },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete ${model.name} from this phone? You can download it again later (${fmtBytes(total)}).",
            "Delete",
            onConfirm = {
                if (voice) {
                    if (tts.modelId == model.id) app.readAloud.unload()
                    if (selected) app.settings.setTtsModelId(ModelCatalog.SYSTEM_TTS_ID)
                } else if (selected) app.selectModel(ModelCatalog.SYSTEM_ID)
                app.models.delete(model.id)
            },
            onDismiss = { confirmDelete = false },
            cancel = "Keep",
        )
    }
}

/** A small button for the end of a model's row. */
@Composable
private fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = true) {
    val padding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    val modifier = Modifier.height(34.dp)
    val label: @Composable () -> Unit = { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
    if (primary) Button(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = padding) { label() }
    else OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = padding) { label() }
}

/** Shown instead of a button on the model in use. */
@Composable
private fun InUse(loading: Boolean) {
    val c = Voice.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.leaf, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (loading) "Loading…" else "In use", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.ink2)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = Voice.colors.ink3)
        Spacer(Modifier.height(2.dp))
        Text(value, fontFamily = Mono, fontSize = 13.sp, color = Voice.colors.ink)
    }
}
