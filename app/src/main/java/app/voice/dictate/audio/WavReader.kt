package app.voice.dictate.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a PCM WAV file into 16 kHz mono floats (mixes channels down and resamples linearly if needed). */
object WavReader {
    fun decode(bytes: ByteArray, targetRate: Int = 16000): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var channels = 1
        var rate = targetRate
        var bits = 16
        var format = 1
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = buf.getShort(body).toInt()
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> { dataStart = body; dataLen = minOf(len, bytes.size - body) }
            }
            if (dataStart >= 0) break
            pos = body + len + (len and 1)
        }
        require(dataStart >= 0) { "WAV has no data" }
        val frameBytes = channels * bits / 8
        val frames = dataLen / frameBytes
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) {
                val at = dataStart + i * frameBytes + c * bits / 8
                s += when {
                    format == 3 && bits == 32 -> buf.getFloat(at)
                    bits == 16 -> buf.getShort(at) / 32768f
                    bits == 32 -> buf.getInt(at) / 2147483648f
                    bits == 8 -> ((bytes[at].toInt() and 0xFF) - 128) / 128f
                    else -> 0f
                }
            }
            mono[i] = s / channels
        }
        if (rate == targetRate) return mono
        val outLen = (frames.toLong() * targetRate / rate).toInt()
        return FloatArray(outLen) { i ->
            val x = i.toDouble() * rate / targetRate
            val i0 = x.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val t = (x - i0).toFloat()
            mono[i0] * (1 - t) + mono[i1] * t
        }
    }
}
