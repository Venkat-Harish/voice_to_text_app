package app.voice.dictate.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.voice.dictate.asr.ModelCatalog
import app.voice.dictate.dictation.GlassStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "voice-prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class BubbleSide { LEFT, RIGHT }
enum class InsertMethod { AUTO, PASTE }

/** Settings that survive restarts (the prototype's usePrefs store, plus the bubble and text options). */
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val modelId: String = ModelCatalog.SYSTEM_ID,
    val language: String = "en-US",
    val bubbleEnabled: Boolean = true,
    /** Show the bubble only while the keyboard is open (like Wispr Flow). */
    val bubbleOnlyWhenTyping: Boolean = true,
    /** Hide it while an app is full screen (video, games). */
    val bubbleHideFullscreen: Boolean = true,
    /** Opacity after a few idle seconds (0.2..1); 1 = never fades. */
    val bubbleIdleOpacity: Float = 0.4f,
    /** Diameter of the glass bubble and of the mic and speaker buttons it opens. */
    val bubbleSizeDp: Int = BUBBLE_SIZE_DEFAULT,
    /** The glass tint (ARGB) and how solid the glass is (0.3..1). */
    val bubbleColor: Int = GlassStyle.GRAPHITE,
    val bubbleGlassOpacity: Float = GlassStyle.DEFAULT_OPACITY,
    val haptics: Boolean = true,
    val removeFillers: Boolean = false,
    val smartSpacing: Boolean = true,
    val insertMethod: InsertMethod = InsertMethod.AUTO,
    /** Where the bubble rests: the screen edge, its height as a fraction of the screen (−1 = just above the keyboard), and whether it is tucked into the edge. */
    val bubbleSide: BubbleSide = BubbleSide.RIGHT,
    val bubbleYFraction: Float = -1f,
    val bubbleTucked: Boolean = false,
    /** Read aloud: the voice engine, its voice (null = the model's first) and speed. */
    val ttsModelId: String = ModelCatalog.SYSTEM_TTS_ID,
    val ttsVoice: String? = null,
    val ttsSpeed: Float = 1f,
    /** Offer read aloud (the speaker button) in the bubble's menu. */
    val speakerButton: Boolean = true,
    /** How many days dictations stay in History: [HISTORY_FOREVER], [HISTORY_NEVER] (don't save), or 1..[HISTORY_MAX_DAYS]. */
    val historyDays: Int = HISTORY_FOREVER,
    /** Free the speech and voice models from memory after they sit unused for [modelUnloadSec] seconds. */
    val modelUnloadEnabled: Boolean = true,
    val modelUnloadSec: Int = MODEL_UNLOAD_DEFAULT_SEC,
)

/** Opacities are chosen on 5 % steps; snap stored values so the slider thumb and label always agree. */
fun onFivePercentGrid(v: Float): Float = kotlin.math.round(v * 20f) / 20f

/** Read-aloud speeds offered in Settings (as in the prototype). */
val TTS_SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f)

/** History retention ([Settings.historyDays]): keep everything, or save nothing. Any other value is a number of days. */
const val HISTORY_FOREVER = -1
const val HISTORY_NEVER = 0
const val HISTORY_MAX_DAYS = 3650

/** How long an unused model may stay loaded ([Settings.modelUnloadSec]), in seconds. */
const val MODEL_UNLOAD_MIN_SEC = 5
const val MODEL_UNLOAD_MAX_SEC = 60 * 60
const val MODEL_UNLOAD_DEFAULT_SEC = 60

/** "45 s", "2 min", "1 min 30 s". */
fun formatDuration(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return when {
        m == 0 -> "$s s"
        s == 0 -> "$m min"
        else -> "$m min $s s"
    }
}

/** "Forever", "Off", "1 day", "30 days". */
fun historyLabel(days: Int): String = when {
    days < 0 -> "Forever"
    days == HISTORY_NEVER -> "Off"
    days == 1 -> "1 day"
    else -> "$days days"
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val modelId = stringPreferencesKey("modelId")
        val language = stringPreferencesKey("language")
        val bubbleEnabled = booleanPreferencesKey("bubbleEnabled")
        val bubbleOnlyWhenTyping = booleanPreferencesKey("bubbleOnlyWhenTyping")
        val bubbleSize = stringPreferencesKey("bubbleSize")
        val haptics = booleanPreferencesKey("haptics")
        val removeFillers = booleanPreferencesKey("removeFillers")
        val smartSpacing = booleanPreferencesKey("smartSpacing")
        val insertMethod = stringPreferencesKey("insertMethod")
        val bubbleHideFullscreen = booleanPreferencesKey("bubbleHideFullscreen")
        val bubbleIdleOpacity = floatPreferencesKey("bubbleIdleOpacity")
        val bubbleSizeDp = intPreferencesKey("bubbleSizeDp")
        val bubbleColor = intPreferencesKey("bubbleColor")
        val bubbleGlassOpacity = floatPreferencesKey("bubbleGlassOpacity")
        val bubbleSide = stringPreferencesKey("bubbleSide")
        val bubbleYFraction = floatPreferencesKey("bubbleYFraction")
        val bubbleTucked = booleanPreferencesKey("bubbleTucked")
        val ttsModelId = stringPreferencesKey("ttsModelId")
        val ttsVoice = stringPreferencesKey("ttsVoice")
        val ttsSpeed = floatPreferencesKey("ttsSpeed")
        val speakerButton = booleanPreferencesKey("speakerButton")
        val historyDays = intPreferencesKey("historyDays")
        val modelUnloadEnabled = booleanPreferencesKey("modelUnloadEnabled")
        val modelUnloadSec = intPreferencesKey("modelUnloadSec")
    }

    private fun read(p: Preferences) = Settings(
        theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        modelId = p[K.modelId]?.takeIf { ModelCatalog.speechById(it) != null } ?: ModelCatalog.SYSTEM_ID,
        language = p[K.language] ?: "en-US",
        bubbleEnabled = p[K.bubbleEnabled] ?: true,
        bubbleOnlyWhenTyping = p[K.bubbleOnlyWhenTyping] ?: true,
        bubbleHideFullscreen = p[K.bubbleHideFullscreen] ?: true,
        bubbleIdleOpacity = p[K.bubbleIdleOpacity]?.let(::onFivePercentGrid)?.coerceIn(0.2f, 1f) ?: 0.4f,
        bubbleSizeDp = p[K.bubbleSizeDp]?.coerceIn(BUBBLE_SIZE_MIN, BUBBLE_SIZE_MAX)
            ?: legacyBubbleSizeDp(p[K.bubbleSize]) ?: BUBBLE_SIZE_DEFAULT,
        bubbleColor = p[K.bubbleColor] ?: GlassStyle.GRAPHITE,
        bubbleGlassOpacity = p[K.bubbleGlassOpacity]?.let(::onFivePercentGrid)?.coerceIn(GlassStyle.MIN_OPACITY, 1f)
            ?: GlassStyle.DEFAULT_OPACITY,
        haptics = p[K.haptics] ?: true,
        removeFillers = p[K.removeFillers] ?: false,
        smartSpacing = p[K.smartSpacing] ?: true,
        insertMethod = p[K.insertMethod]?.let { runCatching { InsertMethod.valueOf(it) }.getOrNull() } ?: InsertMethod.AUTO,
        bubbleSide = p[K.bubbleSide]?.let { runCatching { BubbleSide.valueOf(it) }.getOrNull() } ?: BubbleSide.RIGHT,
        bubbleYFraction = p[K.bubbleYFraction] ?: -1f,
        bubbleTucked = p[K.bubbleTucked] ?: false,
        ttsModelId = p[K.ttsModelId]?.takeIf { ModelCatalog.voiceModelById(it) != null } ?: ModelCatalog.SYSTEM_TTS_ID,
        ttsVoice = p[K.ttsVoice],
        ttsSpeed = p[K.ttsSpeed] ?: 1f,
        speakerButton = p[K.speakerButton] ?: true,
        historyDays = p[K.historyDays]?.let { if (it < 0) HISTORY_FOREVER else it.coerceAtMost(HISTORY_MAX_DAYS) } ?: HISTORY_FOREVER,
        modelUnloadEnabled = p[K.modelUnloadEnabled] ?: true,
        modelUnloadSec = p[K.modelUnloadSec]?.coerceIn(MODEL_UNLOAD_MIN_SEC, MODEL_UNLOAD_MAX_SEC) ?: MODEL_UNLOAD_DEFAULT_SEC,
    )

    // Read once up front so the bubble and the model loader start with the real values, not defaults.
    private val initial: Settings = runBlocking { read(context.dataStore.data.first()) }

    val flow: StateFlow<Settings> = context.dataStore.data.map(::read).stateIn(scope, SharingStarted.Eagerly, initial)

    val value: Settings get() = flow.value

    private val writeScope = scope

    private fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        writeScope.launch { context.dataStore.edit(block) }
    }

    fun setTheme(v: ThemeMode) = edit { it[K.theme] = v.name }
    fun setModelId(v: String) = edit { it[K.modelId] = v }
    fun setLanguage(v: String) = edit { it[K.language] = v }
    fun setBubbleEnabled(v: Boolean) = edit { it[K.bubbleEnabled] = v }
    fun setBubbleOnlyWhenTyping(v: Boolean) = edit { it[K.bubbleOnlyWhenTyping] = v }
    fun setBubbleSizeDp(v: Int) = edit {
        it[K.bubbleSizeDp] = v.coerceIn(BUBBLE_SIZE_MIN, BUBBLE_SIZE_MAX)
        it.remove(K.bubbleSize) // the old Small / Medium / Large choice
    }
    fun setBubbleColor(v: Int) = edit { it[K.bubbleColor] = v or 0xFF000000.toInt() }
    fun setBubbleGlassOpacity(v: Float) = edit { it[K.bubbleGlassOpacity] = v.coerceIn(GlassStyle.MIN_OPACITY, 1f) }
    fun setHaptics(v: Boolean) = edit { it[K.haptics] = v }
    fun setRemoveFillers(v: Boolean) = edit { it[K.removeFillers] = v }
    fun setSmartSpacing(v: Boolean) = edit { it[K.smartSpacing] = v }
    fun setInsertMethod(v: InsertMethod) = edit { it[K.insertMethod] = v.name }
    fun setBubbleHideFullscreen(v: Boolean) = edit { it[K.bubbleHideFullscreen] = v }
    fun setBubbleIdleOpacity(v: Float) = edit { it[K.bubbleIdleOpacity] = v.coerceIn(0.2f, 1f) }

    /** Saves where the bubble rests after you drag or tuck it. */
    fun setBubbleDock(side: BubbleSide, yFraction: Float, tucked: Boolean) = edit {
        it[K.bubbleSide] = side.name
        it[K.bubbleYFraction] = yFraction
        it[K.bubbleTucked] = tucked
    }

    /** Back to the right edge, just above the keyboard, not tucked. */
    fun resetBubble() = edit {
        it.remove(K.bubbleSide)
        it.remove(K.bubbleYFraction)
        it.remove(K.bubbleTucked)
    }

    /** Voice ids differ per model, so switching model goes back to that model's default voice. */
    fun setTtsModelId(v: String) = edit {
        if (it[K.ttsModelId] != v) { it[K.ttsModelId] = v; it.remove(K.ttsVoice) }
    }
    fun setTtsVoice(v: String) = edit { it[K.ttsVoice] = v }
    fun setTtsSpeed(v: Float) = edit { it[K.ttsSpeed] = v }
    fun setSpeakerButton(v: Boolean) = edit { it[K.speakerButton] = v }
    fun setHistoryDays(v: Int) = edit { it[K.historyDays] = if (v < 0) HISTORY_FOREVER else v.coerceAtMost(HISTORY_MAX_DAYS) }
    fun setModelUnloadEnabled(v: Boolean) = edit { it[K.modelUnloadEnabled] = v }
    fun setModelUnloadSec(v: Int) = edit { it[K.modelUnloadSec] = v.coerceIn(MODEL_UNLOAD_MIN_SEC, MODEL_UNLOAD_MAX_SEC) }
}
