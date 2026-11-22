package app.voice.dictate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import app.voice.dictate.asr.SAMPLE_RATE
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Microphone capture as 16 kHz mono float blocks of 1280 samples (80 ms), each with an RMS level.
 * The Android counterpart of the prototype's capture-worklet.js: AudioRecord records at 16 kHz directly,
 * so no resampling is needed.
 */
class AudioCapture {
    @Volatile private var running = false
    private var thread: Thread? = null

    /**
     * Starts recording. Returns false if the microphone could not be opened.
     * [onSilenced] fires once if the first 1.5 s are pure digital silence, which is what Android
     * delivers when it blocks background microphone access.
     */
    @SuppressLint("MissingPermission")
    fun start(onAudio: (FloatArray) -> Unit, onLevel: (Float) -> Unit, onSilenced: () -> Unit = {}): Boolean {
        stop()
        val useFloat = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT) > 0
        val encoding = if (useFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
        if (minBuf <= 0) return false
        val bytesPerSample = if (useFloat) 4 else 2
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                encoding,
                max(minBuf, SAMPLE_RATE / 2 * bytesPerSample),
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord failed", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }
        try { record.startRecording() } catch (e: Exception) { record.release(); return false }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) { record.release(); return false }

        running = true
        thread = Thread({
            val block = FloatArray(BLOCK)
            val shorts = if (useFloat) null else ShortArray(BLOCK)
            var heard = 0
            var peak = 0f
            var silencedReported = false
            try {
                while (running) {
                    var n = 0
                    while (n < BLOCK && running) {
                        val r = if (useFloat) record.read(block, n, BLOCK - n, AudioRecord.READ_BLOCKING)
                        else record.read(shorts!!, n, BLOCK - n)
                        if (r < 0) { running = false; break }
                        n += r
                    }
                    if (n < BLOCK) break
                    if (!useFloat) for (i in 0 until BLOCK) block[i] = shorts!![i] / 32768f
                    var sum = 0.0
                    for (v in block) { sum += v * v; peak = max(peak, abs(v)) }
                    heard += BLOCK
                    if (!silencedReported && heard >= SAMPLE_RATE * 3 / 2) {
                        silencedReported = true
                        if (peak == 0f) onSilenced()
                    }
                    onLevel(sqrt(sum / BLOCK).toFloat())
                    onAudio(block.copyOf())
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "mic").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Stops recording and waits until the last block has been delivered. */
    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    companion object {
        const val BLOCK = 1280
        private const val TAG = "AudioCapture"
    }
}
