package app.voice.dictate

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.WindowManager
import androidx.core.content.IntentCompat

/**
 * Reads the clipboard for the bubble's speaker button. Android 10+ lets an app read the clipboard only while one
 * of its windows has focus, and the bubble never has focus. So this activity opens without drawing anything,
 * takes focus for a moment, reads the clip, hands it back to the bubble service and closes.
 */
class ClipboardGrabActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var receiver: ResultReceiver? = null
    private val giveUp = Runnable { deliver(RESULT_FAILED, null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touches pass through to the app underneath, and the keyboard stays with that app's text field.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        receiver = receiverFrom(intent)
        main.postDelayed(giveUp, TIMEOUT_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapped again before this one closed: answer the earlier request, then serve the new one.
        receiver?.send(RESULT_FAILED, null)
        receiver = receiverFrom(intent)
        if (hasWindowFocus()) read()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) read()
    }

    private fun read() {
        val text = clipboardText()
        deliver(if (text != null) RESULT_TEXT else RESULT_EMPTY, text)
    }

    private fun deliver(code: Int, text: String?) {
        main.removeCallbacks(giveUp)
        val r = receiver
        receiver = null
        r?.send(code, text?.let { Bundle().apply { putString(KEY_TEXT, it) } })
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUp)
        receiver?.send(RESULT_FAILED, null)
        receiver = null
        super.onDestroy()
    }

    private fun clipboardText(): String? = runCatching {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun receiverFrom(intent: Intent?): ResultReceiver? =
        intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RECEIVER, ResultReceiver::class.java) }

    companion object {
        const val EXTRA_RECEIVER = "receiver"
        const val KEY_TEXT = "text"
        /** The clipboard held text (in [KEY_TEXT]). */
        const val RESULT_TEXT = 1
        /** The clipboard is empty, or holds no text. */
        const val RESULT_EMPTY = 0
        /** Focus never came, so the clipboard couldn't be read. */
        const val RESULT_FAILED = 2
        private const val TIMEOUT_MS = 1500L
    }
}
