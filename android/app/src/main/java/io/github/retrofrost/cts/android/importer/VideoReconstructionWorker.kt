package io.github.retrofrost.cts.android.importer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.retrofrost.cts.android.MainActivity
import io.github.retrofrost.cts.android.R
import io.github.retrofrost.cts.android.model.VisualModel
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class VideoReconstructionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val notifications = ReconstructionNotifications(appContext, id)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceText = inputData.getString(KEY_SOURCE_URI)
            ?: return@withContext Result.failure(errorData("Missing comparison-video source."))
        val sourceName = inputData.getString(KEY_SOURCE_NAME).orEmpty().ifBlank { "Comparison video" }
        val source = Uri.parse(sourceText)

        notifications.createChannel()
        setForeground(notifications.foreground(0, "Opening comparison video", sourceName))

        try {
            var lastPercent = -1
            var lastPhase = ""
            val result = VideoComparisonImporter.reconstruct(
                context = applicationContext,
                source = source,
                sourceName = sourceName,
                onProgress = { progress ->
                    val phase = progress.phase.label
                    if (progress.percent != lastPercent || phase != lastPhase) {
                        lastPercent = progress.percent
                        lastPhase = phase
                        val data = progressData(progress)
                        setProgressAsync(data)
                        notifications.progress(progress.percent, phase, progress.detail)
                    }
                },
            )
            val resultDirectory = File(applicationContext.filesDir, "video-reconstruction-results").apply { mkdirs() }
            val resultFile = File(resultDirectory, "$id.json")
            resultFile.writeText(VideoReconstructionResultJson.encode(result))
            markPending(resultFile)
            setProgress(progressData(VideoReconstructionProgress(
                VideoReconstructionPhase.SavingArtwork,
                1,
                1,
                "Ready to review ${result.cards.size} recovered cards",
            )))
            notifications.complete(sourceName, result.cards.size)
            Result.success(
                workDataOf(
                    KEY_RESULT_PATH to resultFile.absolutePath,
                    KEY_SOURCE_NAME to sourceName,
                ),
            )
        } catch (canceled: CancellationException) {
            notifications.canceled(sourceName)
            throw canceled
        } catch (error: Throwable) {
            val message = error.message ?: "Comparison reconstruction stopped unexpectedly."
            notifications.failed(sourceName, message)
            Result.failure(errorData(message))
        }
    }

    private fun markPending(resultFile: File) {
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_PENDING_RESULT, resultFile.absolutePath)
            .putString(PREF_PENDING_WORK_ID, id.toString())
            .apply()
    }

    companion object {
        const val TAG = "cts-video-reconstruction"
        const val KEY_PROGRESS = "progress"
        const val KEY_PHASE = "phase"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_DETAIL = "detail"
        const val KEY_RESULT_PATH = "result_path"
        private const val KEY_SOURCE_URI = "source_uri"
        private const val KEY_SOURCE_NAME = "source_name"
        private const val PREFS_NAME = "cts-video-reconstruction"
        private const val PREF_PENDING_RESULT = "pending_result"
        private const val PREF_PENDING_WORK_ID = "pending_work_id"

        fun enqueue(context: Context, source: Uri, sourceName: String): UUID {
            val request = OneTimeWorkRequestBuilder<VideoReconstructionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SOURCE_URI to source.toString(),
                        KEY_SOURCE_NAME to sourceName,
                    ),
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }

        fun progressFrom(data: Data): VideoReconstructionProgress {
            val phase = runCatching {
                VideoReconstructionPhase.valueOf(data.getString(KEY_PHASE).orEmpty())
            }.getOrDefault(VideoReconstructionPhase.Reading)
            return VideoReconstructionProgress(
                phase = phase,
                completed = data.getInt(KEY_COMPLETED, 0),
                total = data.getInt(KEY_TOTAL, 1).coerceAtLeast(1),
                detail = data.getString(KEY_DETAIL).orEmpty().ifBlank { "Background reconstruction is starting" },
            )
        }

        fun readResult(path: String): VideoReconstructionResult =
            VideoReconstructionResultJson.decode(File(path).readText())

        fun peekPendingResult(context: Context): VideoReconstructionResult? {
            val path = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_PENDING_RESULT, null)
                ?: return null
            val file = File(path)
            if (!file.isFile) {
                clearPendingResult(context)
                return null
            }
            return runCatching { readResult(path) }.getOrNull()
        }

        fun clearPendingResult(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val path = prefs.getString(PREF_PENDING_RESULT, null)
            prefs.edit()
                .remove(PREF_PENDING_RESULT)
                .remove(PREF_PENDING_WORK_ID)
                .apply()
            path?.let { runCatching { File(it).delete() } }
        }

        private fun progressData(progress: VideoReconstructionProgress): Data = workDataOf(
            KEY_PROGRESS to progress.percent,
            KEY_PHASE to progress.phase.name,
            KEY_COMPLETED to progress.completed,
            KEY_TOTAL to progress.total,
            KEY_DETAIL to progress.detail,
        )

        private fun errorData(message: String): Data = workDataOf(KEY_DETAIL to message)
    }
}

private object VideoReconstructionResultJson {
    fun encode(result: VideoReconstructionResult): String = JSONObject().apply {
        put("sourceName", result.sourceName)
        put("durationSeconds", result.durationSeconds.toDouble())
        put("detectedModel", result.detectedModel.name)
        put("warnings", JSONArray(result.warnings))
        put("cards", JSONArray().apply {
            result.cards.forEach { card ->
                put(JSONObject().apply {
                    put("id", card.id)
                    put("badgePrimary", card.badgePrimary)
                    put("badgeSecondary", card.badgeSecondary)
                    put("title", card.title)
                    put("description", card.description)
                    put("artworkPath", card.artworkPath)
                    put("sourceTimeSeconds", card.sourceTimeSeconds.toDouble())
                    put("confidence", card.confidence.toDouble())
                    put("warnings", JSONArray(card.warnings))
                })
            }
        })
    }.toString()

    fun decode(text: String): VideoReconstructionResult {
        val root = JSONObject(text)
        val cardsJson = root.getJSONArray("cards")
        val cards = buildList {
            for (index in 0 until cardsJson.length()) {
                val card = cardsJson.getJSONObject(index)
                add(
                    ReconstructedCard(
                        id = card.optString("id").ifBlank { UUID.randomUUID().toString() },
                        badgePrimary = card.optString("badgePrimary"),
                        badgeSecondary = card.optString("badgeSecondary"),
                        title = card.optString("title"),
                        description = card.optString("description"),
                        artworkPath = card.getString("artworkPath"),
                        sourceTimeSeconds = card.optDouble("sourceTimeSeconds", 0.0).toFloat(),
                        confidence = card.optDouble("confidence", 0.0).toFloat(),
                        warnings = card.optJSONArray("warnings").toStringList(),
                    ),
                )
            }
        }
        return VideoReconstructionResult(
            sourceName = root.optString("sourceName").ifBlank { "Comparison video" },
            durationSeconds = root.optDouble("durationSeconds", 0.0).toFloat(),
            detectedModel = runCatching {
                VisualModel.valueOf(root.getString("detectedModel"))
            }.getOrDefault(VisualModel.Males),
            cards = cards,
            warnings = root.optJSONArray("warnings").toStringList(),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) add(optString(index))
        }
    }
}

private class ReconstructionNotifications(
    private val context: Context,
    workId: UUID,
) {
    private val notificationId = 0x435452 + (workId.hashCode() and 0x0fffffff)
    private val manager = NotificationManagerCompat.from(context)
    private val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(workId)

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "CTS reconstruction",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Comparison-video reconstruction progress"
                setShowBadge(false)
            },
        )
    }

    fun foreground(percent: Int, stage: String, detail: String): ForegroundInfo {
        val notification = progressNotification(percent, stage, detail)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    fun progress(percent: Int, stage: String, detail: String) {
        runCatching { manager.notify(notificationId, progressNotification(percent, stage, detail)) }
    }

    fun complete(sourceName: String, count: Int) {
        val notification = baseBuilder()
            .setContentTitle("Comparison reconstructed")
            .setContentText("$count cards recovered from $sourceName · tap to review")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$count editable cards were recovered from $sourceName. Open CTS to review the text and artwork."))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    fun failed(sourceName: String, message: String) {
        val notification = baseBuilder()
            .setContentTitle("Reconstruction failed")
            .setContentText(sourceName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    fun canceled(sourceName: String) {
        val notification = baseBuilder()
            .setContentTitle("Reconstruction canceled")
            .setContentText(sourceName)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    private fun progressNotification(percent: Int, stage: String, detail: String) = baseBuilder()
        .setContentTitle(stage)
        .setContentText(detail)
        .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        .setContentIntent(openAppIntent())
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, percent.coerceIn(0, 100), false)
        .build()

    private fun baseBuilder() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_cts)
        .setColor(0xff7057e8.toInt())
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CHANNEL_ID = "cts_reconstruction"
    }
}
