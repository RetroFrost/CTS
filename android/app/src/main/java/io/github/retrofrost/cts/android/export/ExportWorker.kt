package io.github.retrofrost.cts.android.export

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
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.persistence.ProjectJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

class ExportWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val notifications = ExportNotifications(appContext, id)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val projectPath = inputData.getString(KEY_PROJECT_PATH)
            ?: return@withContext Result.failure(errorData("Missing export project."))
        val destinationText = inputData.getString(KEY_DESTINATION_URI)
            ?: return@withContext Result.failure(errorData("Missing export destination."))
        val displayName = inputData.getString(KEY_DISPLAY_NAME).orEmpty().ifBlank { "CTS comparison.mp4" }
        val projectFile = File(projectPath)
        val destination = Uri.parse(destinationText)

        notifications.createChannels()
        setForeground(notifications.foreground(0, "Preparing export", displayName))

        try {
            val project = ProjectJson.decode(projectFile.readText()).normalized()
            MediaExportEngine(
                context = applicationContext,
                project = project,
                shouldStop = { isStopped },
                onProgress = { percent, stage, detail ->
                    notifications.progress(percent, stage, detail)
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS to percent,
                            KEY_STAGE to stage,
                            KEY_DETAIL to detail,
                        ),
                    )
                },
            ).export(destination)
            notifications.complete(destination, displayName)
            Result.success(
                workDataOf(
                    KEY_DESTINATION_URI to destination.toString(),
                    KEY_DISPLAY_NAME to displayName,
                ),
            )
        } catch (canceled: CancellationException) {
            notifications.canceled(displayName)
            Result.failure(errorData("Export canceled."))
        } catch (error: Throwable) {
            notifications.failed(displayName, error.message ?: "The encoder stopped unexpectedly.")
            Result.failure(errorData(error.message ?: "The encoder stopped unexpectedly."))
        } finally {
            projectFile.delete()
        }
    }

    companion object {
        const val TAG = "cts-background-export"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_DETAIL = "detail"
        const val KEY_DESTINATION_URI = "destination_uri"
        const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_PROJECT_PATH = "project_path"

        fun enqueue(
            context: Context,
            project: CtsProject,
            destination: Uri,
            displayName: String,
        ): UUID {
            val directory = File(context.cacheDir, "cts-export-jobs").apply { mkdirs() }
            val jobFile = File(directory, "${UUID.randomUUID()}.cts.json")
            jobFile.writeText(ProjectJson.encode(project.normalized()))
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PROJECT_PATH to jobFile.absolutePath,
                        KEY_DESTINATION_URI to destination.toString(),
                        KEY_DISPLAY_NAME to displayName,
                    ),
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }

        private fun errorData(message: String): Data = workDataOf(KEY_DETAIL to message)
    }
}

private class ExportNotifications(
    private val context: Context,
    workId: UUID,
) {
    private val notificationId = 0x435453 + (workId.hashCode() and 0x0fffffff)
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val systemManager = context.getSystemService(NotificationManager::class.java)
        systemManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "CTS exports",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Encoding progress and completed CTS videos"
                setShowBadge(true)
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

    fun complete(destination: Uri, displayName: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(destination, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = baseBuilder()
            .setContentTitle("CTS video encoded")
            .setContentText("$displayName is ready · tap to open")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$displayName is ready. Tap this notification to open the encoded MP4."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    fun failed(displayName: String, message: String) {
        val notification = baseBuilder()
            .setContentTitle("CTS export failed")
            .setContentText(displayName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    fun canceled(displayName: String) {
        val notification = baseBuilder()
            .setContentTitle("CTS export canceled")
            .setContentText(displayName)
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
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, percent.coerceIn(0, 100), false)
        .build()

    private fun baseBuilder() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_cts)
        .setColor(0xff7057e8.toInt())
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
        const val CHANNEL_ID = "cts_exports"
    }
}
