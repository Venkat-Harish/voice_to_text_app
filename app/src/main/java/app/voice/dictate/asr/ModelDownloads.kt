package app.voice.dictate.asr

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.voice.dictate.R
import app.voice.dictate.VoiceApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModelStatus { CHECKING, NONE, PARTIAL, DOWNLOADING, READY }

data class ModelState(val status: ModelStatus = ModelStatus.CHECKING, val bytes: Long = 0, val error: String? = null)

/**
 * Download state for every model, combining what is on disk with the running WorkManager jobs.
 * Mirrors the prototype's useModels store and its download()/cancelDownload()/remove() helpers.
 */
class ModelRepository(private val context: Context, private val store: ModelStore, private val scope: CoroutineScope) {
    private val wm get() = WorkManager.getInstance(context)
    private val _states = MutableStateFlow(ModelCatalog.ALL.associate { it.id to ModelState() })
    val states: StateFlow<Map<String, ModelState>> = _states

    private val _storage = MutableStateFlow(0L to 0L) // used, free
    val storage: StateFlow<Pair<Long, Long>> = _storage

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { store.pruneStale() }
            refresh()
        }
        for (m in ModelCatalog.ALL.filter { it.needsDownload }) {
            scope.launch {
                wm.getWorkInfosForUniqueWorkFlow(workName(m.id)).collect { infos -> onWork(m, infos.firstOrNull()) }
            }
        }
    }

    private fun patch(id: String, f: (ModelState) -> ModelState) = _states.update { it + (id to f(it[id] ?: ModelState())) }

    private suspend fun onWork(model: SpeechModel, info: WorkInfo?) {
        when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                val bytes = info.progress.getLong(ModelDownloadWorker.KEY_BYTES, -1L)
                patch(model.id) {
                    ModelState(ModelStatus.DOWNLOADING, if (bytes >= 0) bytes else it.bytes, null)
                }
            }
            WorkInfo.State.FAILED -> {
                refresh(model)
                val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                patch(model.id) { it.copy(error = error) }
            }
            else -> refresh(model)
        }
    }

    suspend fun refresh() {
        for (m in ModelCatalog.ALL) refresh(m)
    }

    private suspend fun refresh(model: SpeechModel) = withContext(Dispatchers.IO) {
        val current = _states.value[model.id]
        if (current?.status == ModelStatus.DOWNLOADING && isRunning(model.id)) return@withContext
        val s = store.status(model)
        patch(model.id) {
            ModelState(
                status = when { s.ready -> ModelStatus.READY; s.bytes > 0 -> ModelStatus.PARTIAL; else -> ModelStatus.NONE },
                bytes = s.bytes,
                error = null,
            )
        }
        _storage.value = store.usedBytes() to store.freeBytes()
    }

    private fun isRunning(id: String): Boolean = runCatching {
        wm.getWorkInfosForUniqueWork(workName(id)).get().any { !it.state.isFinished }
    }.getOrDefault(false)

    fun download(id: String) {
        patch(id) { it.copy(status = ModelStatus.DOWNLOADING, error = null) }
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(id: String) { wm.cancelUniqueWork(workName(id)) }

    fun delete(id: String) {
        val model = ModelCatalog.byId(id) ?: return
        scope.launch(Dispatchers.IO) {
            store.delete(model)
            refresh(model)
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        for (m in ModelCatalog.ALL.filter { it.needsDownload }) { wm.cancelUniqueWork(workName(m.id)); store.delete(m) }
        refresh()
    }

    companion object {
        fun workName(id: String) = "download-$id"
    }
}

/** Downloads one model in the foreground so a 700 MB download keeps going when you leave the app. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceApp
        val model = ModelCatalog.byId(inputData.getString(KEY_MODEL)) ?: return Result.failure()
        val notifyId = NOTIFY_BASE + ModelCatalog.ALL.indexOf(model)
        runCatching { setForeground(foregroundInfo(model, 0, notifyId)) }
        return try {
            var last = 0L
            app.modelStore.download(model) { bytes ->
                val now = System.currentTimeMillis()
                if (now - last > 400 || bytes == model.totalBytes) {
                    last = now
                    setProgressAsync(workDataOf(KEY_BYTES to bytes))
                    notifications.notify(notifyId, notification(model, bytes))
                }
            }
            notifications.cancel(notifyId)
            app.onModelDownloaded(model.id)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val model = ModelCatalog.byId(inputData.getString(KEY_MODEL)) ?: ModelCatalog.ALL[1]
        return foregroundInfo(model, 0, NOTIFY_BASE + ModelCatalog.ALL.indexOf(model))
    }

    private fun foregroundInfo(model: SpeechModel, bytes: Long, id: Int): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification(model, bytes), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(id, notification(model, bytes))

    private fun notification(model: SpeechModel, bytes: Long) =
        NotificationCompat.Builder(applicationContext, VoiceApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Downloading ${model.name}")
            .setContentText("${fmtBytes(bytes)} of ${fmtBytes(model.totalBytes)}")
            .setProgress(1000, ((bytes * 1000) / model.totalBytes.coerceAtLeast(1)).toInt(), bytes == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_BYTES = "bytes"
        const val KEY_ERROR = "error"
        private const val NOTIFY_BASE = 4100
    }
}
