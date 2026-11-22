package app.voice.dictate.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.voice.dictate.VoiceApp
import app.voice.dictate.asr.AsrStatus
import app.voice.dictate.asr.EngineKind
import app.voice.dictate.asr.ModelCatalog
import app.voice.dictate.asr.ModelStatus
import app.voice.dictate.dictation.DictationService
import app.voice.dictate.tts.TtsStatus
import app.voice.dictate.ui.SystemLinks
import app.voice.dictate.ui.components.ConfirmDialog
import app.voice.dictate.ui.components.ModelChip
import app.voice.dictate.ui.components.Notice
import app.voice.dictate.ui.components.TopBar
import app.voice.dictate.ui.components.VoiceCard
import app.voice.dictate.ui.components.Caps
import app.voice.dictate.ui.theme.Serif
import app.voice.dictate.ui.theme.Voice
import kotlin.math.min

private data class SetupState(
    val mic: Boolean,
    val service: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
)

@Composable
fun HomeScreen(goModels: () -> Unit) {
    val context = LocalContext.current
    val app = VoiceApp.instance
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val asr by app.asr.state.collectAsStateWithLifecycle()
    val modelStates by app.models.states.collectAsStateWithLifecycle()
    val serviceRunning by DictationService.isRunning.collectAsStateWithLifecycle()

    var setup by remember { mutableStateOf(readSetup(context)) }
    LifecycleResumeEffect(Unit) {
        setup = readSetup(context)
        onPauseOrDispose { }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup = readSetup(context) }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup = readSetup(context) }

    val model = ModelCatalog.byId(settings.modelId) ?: ModelCatalog.MODELS.first()
    val onDevice = model.engine != EngineKind.SYSTEM
    val hasOnDeviceModel = ModelCatalog.MODELS.any { it.needsDownload && modelStates[it.id]?.status == ModelStatus.READY }
    val setupDone = setup.mic && (setup.service || serviceRunning)

    LaunchedEffect(Unit) {
        app.asr.errors.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }

    Column(Modifier.fillMaxSize().background(Voice.colors.paper)) {
        TopBar("Voice") {
            val dot = when {
                !onDevice -> Voice.colors.leaf
                asr.modelId == model.id && asr.status in setOf(AsrStatus.READY, AsrStatus.LISTENING, AsrStatus.STOPPING) -> Voice.colors.leaf
                asr.status == AsrStatus.LOADING -> Voice.colors.turmeric
                else -> Voice.colors.ink3
            }
            ModelChip(model.name, dot, goModels)
        }

        if (!setupDone) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SetupCard(
                    setup = setup,
                    serviceOn = setup.service || serviceRunning,
                    onDevice = onDevice && hasOnDeviceModel,
                    onMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onService = { SystemLinks.openAccessibility(context) },
                    onAppInfo = { SystemLinks.openAppInfo(context) },
                    onNotifications = { if (Build.VERSION.SDK_INT >= 33) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onBattery = { SystemLinks.requestBatteryExemption(context) },
                    onModels = goModels,
                )
                TranscribePanel(Modifier.heightIn(min = 420.dp), onDevice, goModels)
            }
        } else {
            TryBubbleField(settings.bubbleEnabled)
            if (!hasOnDeviceModel) {
                Notice(
                    "Using Android's built-in recognizer. Download a model for private, offline dictation.",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    action = "Models",
                    onAction = goModels,
                )
            }
            TranscribePanel(Modifier.weight(1f), onDevice, goModels)
        }
    }
}

private fun readSetup(context: android.content.Context) = SetupState(
    mic = SystemLinks.hasMic(context),
    service = DictationService.isEnabled(context),
    notifications = SystemLinks.hasNotifications(context),
    battery = SystemLinks.ignoresBatteryOptimizations(context),
)

@Composable
private fun SetupCard(
    setup: SetupState,
    serviceOn: Boolean,
    onDevice: Boolean,
    onMic: () -> Unit,
    onService: () -> Unit,
    onAppInfo: () -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onModels: () -> Unit,
) {
    var showRestricted by rememberSaveable { mutableStateOf(false) }
    VoiceCard(Modifier.padding(16.dp).fillMaxWidth()) {
        Caps("One-time setup")
        Spacer(Modifier.height(8.dp))
        Text("Talk into any app", style = MaterialTheme.typography.headlineMedium, color = Voice.colors.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Whenever you type, a small glass bubble appears by the keyboard. Hold it, speak, and let go: your words are typed into the text field you're in (WhatsApp, Gmail, anywhere). Tap it for the mic and read-aloud buttons.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        SetupStep(
            done = setup.mic,
            title = "Allow the microphone",
            body = "So Voice can hear you.",
            action = "Allow",
            onAction = onMic,
        )
        SetupStep(
            done = serviceOn,
            title = "Turn on the Voice bubble",
            body = "In Accessibility, open \"Voice bubble\" and switch it on. This lets the bubble float over other apps and type into their text fields.",
            action = "Open settings",
            onAction = onService,
        ) {
            if (!serviceOn) {
                TextButton(onClick = { showRestricted = !showRestricted }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text("Is the switch greyed out?", fontSize = 13.sp, color = Voice.colors.ink2)
                }
                if (showRestricted) {
                    Text(
                        "Android 13 and later restrict this for apps installed outside the Play Store. Open App info, tap ⋮ (top right), choose \"Allow restricted settings\", then turn the bubble on again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onAppInfo) { Text("Open App info", color = Voice.colors.ink, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            SetupStep(
                done = setup.notifications,
                title = "Allow notifications",
                body = "Optional: shows progress while a model downloads.",
                action = "Allow",
                onAction = onNotifications,
            )
        }
        SetupStep(
            done = onDevice,
            title = "Choose a speech model",
            body = "Optional: Android's built-in recognizer works now. Download a model for private, offline dictation.",
            action = "Models",
            onAction = onModels,
        )
        SetupStep(
            done = setup.battery,
            title = "Keep Voice running",
            body = "Optional: stops Android from closing the bubble to save battery.",
            action = "Allow",
            onAction = onBattery,
            last = true,
        )
    }
}

@Composable
private fun SetupStep(
    done: Boolean,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    last: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    val c = Voice.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "To do",
            tint = if (done) c.leaf else c.ink3,
            modifier = Modifier.padding(top = 2.dp).size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodySmall)
            extra()
        }
        if (!done) {
            Spacer(Modifier.size(8.dp))
            Button(onClick = onAction, contentPadding = ButtonDefaults.ContentPadding) { Text(action, fontSize = 13.sp) }
        }
    }
    if (!last) Spacer(Modifier.height(2.dp))
}

@Composable
private fun TryBubbleField(enabled: Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        placeholder = { Text(if (enabled) "Try it: tap here, then hold the bubble by the keyboard and speak" else "The bubble is off (Settings → Bubble)") },
        minLines = 2,
        maxLines = 4,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Voice.colors.card,
            unfocusedContainerColor = Voice.colors.card,
            focusedBorderColor = Voice.colors.turmeric,
            unfocusedBorderColor = Voice.colors.line,
        ),
    )
}

/** The prototype's Transcribe page: transcript card, status line and the mic dock. */
@Composable
private fun TranscribePanel(modifier: Modifier, onDevice: Boolean, goModels: () -> Unit) {
    val context = LocalContext.current
    val app = VoiceApp.instance
    val c = Voice.colors
    val paragraphs by app.transcript.paragraphs.collectAsStateWithLifecycle()
    val partial by app.transcript.partial.collectAsStateWithLifecycle()
    val status by InAppDictation.status.collectAsStateWithLifecycle()
    val source by InAppDictation.source.collectAsStateWithLifecycle()
    val level by InAppDictation.level.collectAsStateWithLifecycle()
    val asr by app.asr.state.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val model = ModelCatalog.byId(settings.modelId)
    val tts by app.readAloud.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.readAloud.messages.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    // Opening this screen brings back a model that was freed while idle.
    LifecycleResumeEffect(Unit) {
        app.wakeSpeechModel()
        onPauseOrDispose { }
    }

    val text = paragraphs.joinToString("\n\n")
    val listening = status != InAppStatus.IDLE
    val engineReady = !onDevice || (asr.modelId == model?.id && asr.status == AsrStatus.READY)
    val idle = status == InAppStatus.IDLE && engineReady
    // Downloaded, but freed from memory after sitting unused: the mic loads it again.
    val unloaded = onDevice && status == InAppStatus.IDLE && asr.status == AsrStatus.IDLE && model != null && app.modelStore.isReady(model)
    val slow = onDevice && status == InAppStatus.LISTENING && (asr.speed ?: 9.0) < 1.0

    Column(modifier) {
        if (slow) {
            Notice(
                "This phone can't keep up with this model live. Moonshine or Android built-in are much lighter.",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                action = "Models",
                onAction = goModels,
            )
        }
        Transcript(Modifier.weight(1f), paragraphs, partial, listening, onDevice)

        // Status line
        val statusText = when {
            tts.reading && tts.status == TtsStatus.LOADING -> tts.stage.ifBlank { "Loading the voice…" }
            tts.reading -> "Reading aloud…"
            listening && status == InAppStatus.STOPPING -> "Finishing the last words…"
            listening && source == "file" -> "Transcribing the recording…"
            listening -> if (model?.live == true) "Listening… words appear as you speak" else "Listening… text settles when you pause"
            onDevice && asr.status == AsrStatus.LOADING -> asr.stage.ifBlank { "Loading model…" }
            onDevice && asr.status == AsrStatus.ERROR -> asr.error ?: "The model couldn't load"
            unloaded -> "Unloaded to save memory · tap the mic to load it"
            onDevice && asr.status == AsrStatus.IDLE -> "Download this model to use it"
            else -> "Tap the mic and start talking"
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onDevice && asr.status == AsrStatus.LOADING) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.turmeric)
                Spacer(Modifier.size(6.dp))
            }
            Text(
                statusText,
                fontSize = 13.sp,
                color = if (onDevice && asr.status == AsrStatus.ERROR) c.chilli else c.ink2,
            )
            if (onDevice && listening && asr.speed != null) {
                Text(" · ${"%.1f".format(asr.speed)}× speed", fontSize = 13.sp, color = c.ink3)
            }
            if (onDevice && asr.status == AsrStatus.ERROR) {
                TextButton(onClick = { app.loadSelected() }) { Text("Retry", color = c.ink) }
            }
        }

        // Dock
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DockButton(Icons.Outlined.ContentCopy, "Copy", enabled = text.isNotEmpty()) { SystemLinks.copy(context, text) }
                DockButton(Icons.Outlined.Share, "Share", enabled = text.isNotEmpty()) { SystemLinks.share(context, text) }
            }
            MicButton(
                live = status == InAppStatus.LISTENING,
                busy = status == InAppStatus.STOPPING,
                enabled = listening || idle || unloaded,
                level = level,
                onClick = {
                    if (unloaded) app.wakeSpeechModel()
                    else if (status == InAppStatus.LISTENING) InAppDictation.stop()
                    else if (!SystemLinks.hasMic(context)) android.widget.Toast.makeText(context, "Allow the microphone first", android.widget.Toast.LENGTH_SHORT).show()
                    else InAppDictation.start(context)
                },
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                // Reads the transcript back, as in the prototype; while reading it is a stop button.
                DockButton(
                    if (tts.reading) Icons.Filled.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                    if (tts.reading) "Stop reading" else "Read aloud",
                    enabled = tts.reading || (text.isNotEmpty() && !listening),
                ) { if (tts.reading) app.readAloud.stop() else app.readAloud.speak(text) }
                DockButton(Icons.Outlined.DeleteOutline, "Clear", enabled = text.isNotEmpty() && !listening) { confirmClear = true }
            }
        }
        if (onDevice) {
            Text(
                "or try the sample recording",
                fontSize = 12.sp,
                color = c.ink3,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
                    .clickable(enabled = idle) { InAppDictation.transcribeSample(context) },
            )
        }
    }
    if (confirmClear) {
        ConfirmDialog("Clear the whole transcript?", "Clear", onConfirm = { app.transcript.clear() }, onDismiss = { confirmClear = false })
    }
}

@Composable
private fun Transcript(
    modifier: Modifier,
    paragraphs: List<String>,
    partial: app.voice.dictate.data.TranscriptStore.Partial?,
    listening: Boolean,
    onDevice: Boolean,
) {
    val c = Voice.colors
    val scroll = rememberScrollState()
    LaunchedEffect(paragraphs, partial) { scroll.animateScrollTo(scroll.maxValue) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(18.dp)),
    ) {
        if (paragraphs.isEmpty() && partial == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (listening) "Listening…" else "Say something.",
                    fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 22.sp, color = c.ink2,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (onDevice) "Everything runs on this phone. Nothing you say is uploaded."
                    else "Uses your phone's speech recognizer.",
                    fontSize = 13.sp, color = c.ink3,
                )
            }
        } else {
            val body = buildAnnotatedString {
                paragraphs.forEachIndexed { i, p ->
                    if (i > 0) append("\n\n")
                    append(p)
                }
                if (partial != null) {
                    if (paragraphs.isNotEmpty()) append(if (partial.inline) " " else "\n\n")
                    withStyle(SpanStyle(color = if (partial.stable) c.ink else c.ink3)) { append(partial.text) }
                }
                if (listening) withStyle(SpanStyle(color = c.turmeric, fontWeight = FontWeight.Bold)) { append(" ▍") }
            }
            Text(
                body,
                modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 18.dp),
                fontSize = 19.sp,
                lineHeight = 29.sp,
                color = c.ink,
            )
        }
    }
}

@Composable
private fun DockButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Voice.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp).border(1.dp, c.line, CircleShape),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = c.card, contentColor = c.ink,
            disabledContainerColor = c.card.copy(alpha = 0.5f), disabledContentColor = c.ink3.copy(alpha = 0.6f),
        ),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp)) }
}

@Composable
fun MicButton(live: Boolean, busy: Boolean, enabled: Boolean, level: Float, onClick: () -> Unit) {
    val c = Voice.colors
    // Animate the ring from the mic level, like the prototype's .mic-ring.
    val scale by animateFloatAsState(if (live) 1f + min(0.45f, level * 6f) else 1f, tween(90), label = "ring")
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (live) 0.22f else 0f }
                .clip(CircleShape)
                .background(c.turmeric),
        )
        Surface(
            onClick = onClick,
            enabled = enabled && !busy,
            shape = CircleShape,
            color = when {
                !enabled -> c.paperDeep
                live || busy -> c.chilli
                else -> c.turmeric
            },
            contentColor = if (live || busy) Color.White else Color(0xFF1E1A16),
            shadowElevation = if (enabled) 6.dp else 0.dp,
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp)
                    live -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
                    else -> Icon(Icons.Filled.Mic, contentDescription = "Start talking", modifier = Modifier.size(34.dp), tint = if (enabled) Color(0xFF1E1A16) else c.ink3)
                }
            }
        }
    }
}
