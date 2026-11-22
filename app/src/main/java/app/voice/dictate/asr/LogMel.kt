package app.voice.dictate.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Incremental log-mel features for NeMo FastConformer models (Nemotron streaming).
// Line-by-line port of the prototype's src/asr/logmel.js, which matches kaldi-native-fbank with
// sherpa-onnx's NeMo options: 16 kHz, 25 ms periodic Hann window, 10 ms hop, 512-point FFT,
// 0.97 pre-emphasis, 128 Slaney mel bins (0–8 kHz), log(max(power, FLT_EPSILON)), no dither,
// no normalization, frames centered like Kaldi with snip_edges=false (first frame starts at −120, reflected).

const val SAMPLE_RATE = 16000
const val MEL_BINS = 128
private const val WIN = 400
private const val HOP = 160
private const val NFFT = 512
private const val NBINS = NFFT / 2 + 1
private const val PRE = 0.97f
private const val EPS = 1.1920929e-7f
private const val OFFSET = HOP / 2 - WIN / 2 // −120
val SILENT_LOG: Float = ln(EPS)

private fun melScale(hz: Double) = if (hz < 1000) hz * 3 / 200 else 15 + 14.545078505785561 * ln(hz / 1000)
private fun melToHz(m: Double) = if (m < 15) m * 200 / 3 else 1000 * exp((m - 15) / 14.545078505785561)

private class MelFilter(val start: Int, val w: FloatArray)

private val FILTERS: Array<MelFilter> = run {
    val lo = melScale(0.0)
    val hi = melScale(SAMPLE_RATE / 2.0)
    val pts = DoubleArray(MEL_BINS + 2) { i -> melToHz(lo + (hi - lo) * i / (MEL_BINS + 1)) }
    Array(MEL_BINS) { m ->
        val l = pts[m]; val c = pts[m + 1]; val r = pts[m + 2]
        val norm = 2 / (r - l) // Slaney area normalization
        var start = -1
        val w = ArrayList<Float>()
        for (k in 0 until NBINS) {
            val f = k.toDouble() * SAMPLE_RATE / NFFT
            val v = max(0.0, min((f - l) / (c - l), (r - f) / (r - c)))
            if (v > 0) {
                if (start < 0) start = k
                w.add((v * norm).toFloat())
            } else if (start >= 0) break
        }
        MelFilter(max(0, start), w.toFloatArray())
    }
}

private val WINDOW = FloatArray(WIN) { i -> (0.5 - 0.5 * cos(2 * PI * i / WIN)).toFloat() }
private val COS = FloatArray(NFFT / 2) { i -> cos(-2 * PI * i / NFFT).toFloat() }
private val SIN = FloatArray(NFFT / 2) { i -> sin(-2 * PI * i / NFFT).toFloat() }
private val REV = IntArray(NFFT).also { rev ->
    val bits = 9 // log2(512)
    for (i in 0 until NFFT) {
        var r = 0
        for (b in 0 until bits) r = r or (((i shr b) and 1) shl (bits - 1 - b))
        rev[i] = r
    }
}

/** In-place iterative radix-2 FFT. */
private fun fft(re: FloatArray, im: FloatArray) {
    for (i in 0 until NFFT) {
        val j = REV[i]
        if (j > i) {
            var t = re[i]; re[i] = re[j]; re[j] = t
            t = im[i]; im[i] = im[j]; im[j] = t
        }
    }
    var size = 2
    while (size <= NFFT) {
        val half = size shr 1
        val step = NFFT / size
        var i = 0
        while (i < NFFT) {
            var k = 0
            for (j in 0 until half) {
                val a = i + j
                val b = a + half
                val tr = re[b] * COS[k] - im[b] * SIN[k]
                val ti = re[b] * SIN[k] + im[b] * COS[k]
                re[b] = re[a] - tr; im[b] = im[a] - ti
                re[a] += tr; im[a] += ti
                k += step
            }
            i += size
        }
        size = size shl 1
    }
}

class LogMel {
    private var buf = FloatArray(0) // samples from absolute index `base`
    private var base = 0
    private var total = 0
    private var next = 0 // next frame index to emit
    private val re = FloatArray(NFFT)
    private val im = FloatArray(NFFT)
    private val power = FloatArray(NBINS)

    /** Feed 16 kHz mono samples; returns zero or more FloatArray(128) frames. */
    fun accept(samples: FloatArray): List<FloatArray> {
        val merged = FloatArray(buf.size + samples.size)
        buf.copyInto(merged)
        samples.copyInto(merged, buf.size)
        buf = merged
        total += samples.size
        val out = ArrayList<FloatArray>()
        while (next * HOP + OFFSET + WIN <= total) out.add(frame(next++))
        val keepFrom = max(0, next * HOP + OFFSET)
        if (keepFrom > base) {
            buf = buf.copyOfRange(keepFrom - base, buf.size)
            base = keepFrom
        }
        return out
    }

    private fun frame(f: Int): FloatArray {
        val start = f * HOP + OFFSET
        for (s in 0 until WIN) {
            var idx = start + s
            if (idx < 0) idx = -idx - 1 // reflect at the very start
            re[s] = buf[idx - base]
        }
        for (s in WIN - 1 downTo 1) re[s] -= PRE * re[s - 1]
        re[0] *= 1 - PRE
        for (s in 0 until WIN) re[s] *= WINDOW[s]
        re.fill(0f, WIN)
        im.fill(0f)
        fft(re, im)
        for (k in 0 until NBINS) power[k] = re[k] * re[k] + im[k] * im[k]
        val frame = FloatArray(MEL_BINS)
        for (m in 0 until MEL_BINS) {
            val filter = FILTERS[m]
            var e = 0f
            for (i in filter.w.indices) e += filter.w[i] * power[filter.start + i]
            frame[m] = ln(max(e, EPS))
        }
        return frame
    }
}
