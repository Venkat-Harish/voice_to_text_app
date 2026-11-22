package app.voice.dictate.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryItem(
    val id: Long,
    val text: String,
    val time: Long,
    /** Package of the app the text went into, if any. */
    val app: String? = null,
    /** "typed", "pasted" or "copied". */
    val result: String = "typed",
)

/**
 * The last 200 dictations from the bubble, so nothing you said is lost if a field didn't take it. Items older
 * than the History setting ([retentionDays]) are dropped, and nothing is saved when it is set to "don't save".
 */
class HistoryRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val retentionDays: () -> Int,
) {
    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HistoryItem.serializer())
    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
            _items.update { current -> (current + loaded).distinctBy { it.id }.take(MAX) }
            prune()
        }
    }

    fun add(text: String, app: String?, result: String) {
        if (retentionDays() == HISTORY_NEVER) return
        val item = HistoryItem(System.currentTimeMillis(), text, System.currentTimeMillis(), app, result)
        _items.update { listOf(item) + it.take(MAX - 1) }
        save()
    }

    fun delete(id: Long) { _items.update { list -> list.filter { it.id != id } }; save() }

    fun clear() { _items.value = emptyList(); save() }

    /** Drops what the History setting no longer keeps (all of it when history is off). */
    fun prune(days: Int = retentionDays()) {
        val before = _items.value
        val after = retain(before, days, System.currentTimeMillis())
        if (after.size == before.size) return
        _items.update { retain(it, days, System.currentTimeMillis()) }
        save()
    }

    /** How many saved dictations a change to [days] would delete. */
    fun countRemovedBy(days: Int): Int = _items.value.size - retain(_items.value, days, System.currentTimeMillis()).size

    private fun save() {
        val snapshot = _items.value
        scope.launch(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(serializer, snapshot)) } }
    }

    companion object {
        private const val MAX = 200
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** The items a retention of [days] keeps at time [now]: all ([HISTORY_FOREVER]), none ([HISTORY_NEVER]), or the last [days] days. */
        fun retain(items: List<HistoryItem>, days: Int, now: Long): List<HistoryItem> = when {
            days < 0 -> items
            days == HISTORY_NEVER -> emptyList()
            else -> {
                val cutoff = now - days * DAY_MS
                items.filter { it.time >= cutoff }
            }
        }
    }
}

/** The in-app transcript (the prototype's useTranscript store); paragraphs persist, the partial line doesn't. */
class TranscriptStore(context: Context, private val scope: CoroutineScope) {
    data class Partial(val text: String, val stable: Boolean, val inline: Boolean)

    private val file = File(context.filesDir, "transcript.json")
    private val serializer = ListSerializer(kotlinx.serialization.serializer<String>())
    private val _paragraphs = MutableStateFlow<List<String>>(emptyList())
    val paragraphs: StateFlow<List<String>> = _paragraphs
    private val _partial = MutableStateFlow<Partial?>(null)
    val partial: StateFlow<Partial?> = _partial

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
            if (_paragraphs.value.isEmpty()) _paragraphs.value = loaded
        }
    }

    fun setPartial(text: String, stable: Boolean, inline: Boolean) {
        _partial.value = if (text.isBlank()) null else Partial(text, stable, inline)
    }

    fun commit(text: String, newParagraph: Boolean) {
        _paragraphs.update { list ->
            if (newParagraph || list.isEmpty()) list + text else list.dropLast(1) + "${list.last()} $text"
        }
        _partial.value = null
        save()
    }

    fun clear() { _paragraphs.value = emptyList(); _partial.value = null; save() }

    private fun save() {
        val snapshot = _paragraphs.value
        scope.launch(Dispatchers.IO) { runCatching { file.writeText(Json.encodeToString(serializer, snapshot)) } }
    }
}
