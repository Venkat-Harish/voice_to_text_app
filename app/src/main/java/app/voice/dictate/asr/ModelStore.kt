package app.voice.dictate.asr

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Stores speech models on this phone. The Android counterpart of the prototype's src/lib/modelStore.js:
// each model lives in filesDir/models/<id>@<revision>/, a file downloads to "<name>.part" and is renamed
// only once its size matches the catalog exactly, and an interrupted download resumes with an HTTP Range
// request. App-private storage stays until the model is deleted in the app or the app is uninstalled.

data class StoredStatus(val ready: Boolean, val bytes: Long, val total: Long)

class ModelStore(context: Context) {
    private val root = File(context.filesDir, "models")

    fun dir(model: SpeechModel) = File(root, "${model.id}@${model.revision}")

    private fun file(model: SpeechModel, f: ModelFile) = File(dir(model), f.name)
    private fun part(model: SpeechModel, f: ModelFile) = File(dir(model), "${f.name}.part")

    private fun bytesOf(model: SpeechModel, f: ModelFile): Long {
        val done = file(model, f)
        if (done.length() == f.size) return f.size
        return part(model, f).length().coerceAtMost(f.size)
    }

    fun status(model: SpeechModel): StoredStatus {
        if (!model.needsDownload) return StoredStatus(true, 0, 0)
        val bytes = model.files.sumOf { bytesOf(model, it) }
        val ready = model.files.all { file(model, it).length() == it.size }
        return StoredStatus(ready, bytes, model.totalBytes)
    }

    fun isReady(model: SpeechModel) = !model.needsDownload || model.files.all { file(model, it).length() == it.size }

    fun delete(model: SpeechModel) { dir(model).deleteRecursively() }

    /** Removes folders from older pinned revisions that no catalog entry uses any more. */
    fun pruneStale() {
        val keep = ModelCatalog.ALL.filter { it.needsDownload }.map { dir(it).name }.toSet()
        root.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes(): Long = runCatching { StatFs(root.apply { mkdirs() }.path).availableBytes }.getOrDefault(0)

    /** Downloads whatever is missing. [onProgress] gets the total bytes on disk for this model. */
    suspend fun download(model: SpeechModel, onProgress: (Long) -> Unit) {
        val already = status(model).bytes
        val needed = model.totalBytes - already
        val free = freeBytes()
        if (free in 1 until (needed * 1.05).toLong()) {
            throw IOException("Not enough free space: needs ${fmtBytes(needed)}, ${fmtBytes(free)} available.")
        }
        var done = 0L
        for (f in model.files) {
            val target = file(model, f)
            if (target.length() == f.size) { done += f.size; onProgress(done); continue }
            try {
                downloadFile(model, f) { onProgress(done + it) }
            } catch (e: IOException) {
                throw IOException("${f.name}: ${e.message}", e)
            }
            done += f.size
            onProgress(done)
        }
    }

    private suspend fun downloadFile(model: SpeechModel, f: ModelFile, progress: (Long) -> Unit) {
        val target = file(model, f)
        val part = part(model, f)
        part.parentFile?.mkdirs()
        var have = part.length()
        if (have > f.size) { part.delete(); have = 0 }
        if (have < f.size) {
            val conn = (URL(model.url(f)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Voice-Android/0.1")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("download failed (HTTP $code)")
                // The server ignored our Range request and sent the whole file: start over.
                if (have > 0 && code == 200) have = 0
                conn.inputStream.use { input ->
                    FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var offset = have
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (offset + n > f.size) throw IOException("larger than expected. Try again.")
                            out.write(buf, 0, n)
                            offset += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 250) { lastReport = now; progress(offset) }
                        }
                        progress(offset)
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        val got = part.length()
        if (got != f.size) throw IOException("stopped at ${fmtBytes(got)} of ${fmtBytes(f.size)}. Tap Resume.")
        target.delete()
        if (!part.renameTo(target)) throw IOException("couldn't save the file. Try again.")
    }
}
