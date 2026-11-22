package app.voice.dictate.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/** Text events from a speech engine, the same shape as the prototype worker's messages. */
sealed interface AsrEvent {
    /** The line still being spoken. [stable] = won't change; [inline] = continues the last paragraph. */
    data class Partial(val text: String, val stable: Boolean, val inline: Boolean) : AsrEvent
    /** Final text. [newParagraph] = starts a new paragraph rather than continuing the last one. */
    data class Commit(val text: String, val newParagraph: Boolean) : AsrEvent
}

typealias Emit = (AsrEvent) -> Unit

/** Something live audio is fed to: a streaming engine (Nemotron) or a [Segmenter] around a clip engine. */
interface StreamTarget {
    val backend: String
    fun reset()
    fun accept(samples: FloatArray, emit: Emit)
    fun finish(emit: Emit)
}

/** A "transcribe this clip" engine (Parakeet, Moonshine). */
interface ClipEngine : AutoCloseable {
    val backend: String
    fun transcribe(audio: FloatArray): String
}

/** Shared ONNX Runtime environment and helpers. All engine calls run on AsrHost's single thread. */
object Ort {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** Threads for the big encoders: the phone's big cores, at most 4. */
    val bigThreads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    fun options(threads: Int): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
    }

    /**
     * Sessions are created from the file path, so the weights are memory-mapped by native code
     * and an external-data file (encoder.q4.data) is found next to its .onnx file.
     */
    fun session(file: File, threads: Int): OrtSession =
        options(threads).use { env.createSession(file.absolutePath, it) }

    fun floats(data: FloatArray, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    fun zeros(vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.allocate(shape.fold(1L) { a, b -> a * b }.toInt()), shape)

    fun int64(v: Long): OnnxTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(v)), longArrayOf(1))

    fun int64(data: LongArray, vararg shape: Long): OnnxTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    fun int32(data: IntArray, vararg shape: Long): OnnxTensor = OnnxTensor.createTensor(env, IntBuffer.wrap(data), shape)

    fun bool(v: Boolean): OnnxTensor = OnnxTensor.createTensor(env, booleanArrayOf(v))
}

/** Reads a float tensor's contents into a new array. */
fun OnnxValue.floats(): FloatArray {
    val fb = (this as OnnxTensor).floatBuffer
    return FloatArray(fb.remaining()).also { fb.get(it) }
}

fun OnnxValue.shape(): LongArray = (this as OnnxTensor).info.shape

fun OnnxValue.firstLong(): Long = (this as OnnxTensor).longBuffer.get(0)

/** Output by name, or by position when the export used a different name. */
operator fun OrtSession.Result.get(name: String, fallbackIndex: Int): OnnxValue =
    get(name).orElse(null) ?: get(fallbackIndex)
