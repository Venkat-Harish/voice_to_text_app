package app.voice.dictate.dictation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import app.voice.dictate.data.InsertMethod

enum class InsertResult { TYPED, PASTED, COPIED }

/**
 * Types dictated text into another app's focused field.
 * 1. ACTION_SET_TEXT with the text spliced in at the cursor (replacing any selection), then move the cursor.
 * 2. If the field refuses, put the text on the clipboard and ACTION_PASTE it.
 * 3. If there is no field at all, leave it on the clipboard so it can be pasted by hand.
 */
class TextInserter(private val context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    fun insert(node: AccessibilityNodeInfo?, text: String, method: InsertMethod, smart: Boolean): InsertResult {
        if (node == null || !node.refresh()) {
            copy(text)
            return InsertResult.COPIED
        }
        val (current, start, end) = readField(node)
        val splice = TextSplicer.splice(current, start, end, text, smart)
        if (method == InsertMethod.AUTO && node.isEditable && setText(node, splice)) return InsertResult.TYPED
        if (paste(node, splice.inserted)) return InsertResult.PASTED
        copy(text)
        return InsertResult.COPIED
    }

    /** The field's real text (not its hint) and selection. */
    private fun readField(node: AccessibilityNodeInfo): Triple<String, Int, Int> {
        val raw = node.text?.toString().orEmpty()
        val showingHint = node.isShowingHintText || (raw.isNotEmpty() && raw == node.hintText?.toString())
        if (showingHint) return Triple("", 0, 0)
        return Triple(raw, node.textSelectionStart, node.textSelectionEnd)
    }

    private fun setText(node: AccessibilityNodeInfo, splice: Splice): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, splice.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, splice.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, splice.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
    }

    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        // Android 10+ only lets the focused app read the clipboard, so the old clip is usually unavailable.
        val old = runCatching { clipboard.primaryClip }.getOrNull()
        clipboard.setPrimaryClip(ClipData.newPlainText("Voice", text))
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (ok && old != null) main.postDelayed({ runCatching { clipboard.setPrimaryClip(old) } }, 800)
        return ok
    }

    fun copy(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Voice", text))
    }
}
