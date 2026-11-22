package app.voice.dictate.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays generated speech sentence by sentence, back to back (the prototype schedules AudioBuffers on one
 * AudioContext; here one AudioTrack stream is fed from a queue). Other audio is ducked while it plays.
 */
class TtsPlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** One read-aloud: [enqueue] sentences, then [endOfInput]; [onDone] runs once everything has played. */
    inner class Playback(sampleRate: Int, private val onDone: (stoppedEarly: Boolean) -> Unit) {
        private val queue = LinkedBlockingQueue<FloatArray>()
        @Volatile private var inputDone = false
        @Volatile private var stopped = false
        private var finished = false
        private var written = 0L
        private val track: AudioTrack
        private val focus: AudioFocusRequest

        init {
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, sampleRate * 4 / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    // A phone call or another app taking over stops reading.
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
                }
                .build()
            audio.requestAudioFocus(focus)
            Thread(::run, "tts-play").apply { priority = Thread.MAX_PRIORITY; start() }
        }

        fun enqueue(samples: FloatArray) { if (!stopped) queue.put(samples) }

        fun endOfInput() { inputDone = true }

        fun stop() {
            stopped = true
            queue.clear()
        }

        private fun run() {
            try {
                track.play()
                while (!stopped) {
                    val chunk = queue.poll(50, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (inputDone && queue.isEmpty()) break
                        continue
                    }
                    var off = 0
                    while (off < chunk.size && !stopped) {
                        val n = track.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING)
                        if (n < 0) { stopped = true; break }
                        off += n
                    }
                    written += off
                }
                if (!stopped) {
                    // Let the last sentence finish playing before releasing the track.
                    val deadline = System.currentTimeMillis() + 15_000
                    while (!stopped && track.playbackHeadPosition.toLong() and 0xFFFFFFFFL < written && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20)
                    }
                }
            } catch (e: Exception) {
                Log.w("TtsPlayer", "playback failed", e)
            } finally {
                runCatching { track.pause(); track.flush() }
                track.release()
                audio.abandonAudioFocusRequest(focus)
                synchronized(this) { if (!finished) { finished = true; onDone(stopped) } }
            }
        }
    }
}
