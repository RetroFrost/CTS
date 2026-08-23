package io.github.retrofrost.cts.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

data class ExportProgress(
    val running: Boolean = false,
    val percent: Int = 0,
    val stage: String = "Ready",
    val detail: String = "",
)

object ExportState {
    private val mutable = MutableStateFlow(ExportProgress())
    val state = mutable.asStateFlow()
    fun update(progress: ExportProgress) { mutable.value = progress }
}

class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Video exports", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            ExportState.update(ExportState.state.value.copy(stage = "Cancelling", detail = "Stopping safely"))
            updateNotification(ExportState.state.value)
            return START_NOT_STICKY
        }
        val projectJson = intent?.getStringExtra(EXTRA_PROJECT)
        val destinationText = intent?.getStringExtra(EXTRA_URI)
        if (projectJson.isNullOrBlank() || destinationText.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        cancelled = false
        val initial = ExportProgress(true, 0, "Preparing", "Starting the GPU renderer")
        ExportState.update(initial)
        startForeground(NOTIFICATION_ID, notification(initial))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CubicalCompare:GpuExport")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        job?.cancel()
        job = scope.launch {
            try {
                val project = StudioProject.fromJson(projectJson)
                HardwareVideoExporter(
                    context = applicationContext,
                    project = project,
                    shouldCancel = { cancelled },
                    onProgress = { percent, stage, detail ->
                        val value = ExportProgress(true, percent.coerceIn(0, 100), stage, detail)
                        ExportState.update(value)
                        updateNotification(value)
                    },
                ).export(Uri.parse(destinationText))
                val done = ExportProgress(false, 100, "Finished", "The MP4 is ready")
                ExportState.update(done)
                updateNotification(done)
            } catch (_: CancellationException) {
                val stopped = ExportProgress(false, 0, "Cancelled", "Export cancelled")
                ExportState.update(stopped)
                updateNotification(stopped)
            } catch (error: Throwable) {
                val failed = ExportProgress(
                    false,
                    0,
                    "Export failed",
                    error.message ?: error::class.java.simpleName,
                )
                ExportState.update(failed)
                updateNotification(failed)
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock = null
                stopForeground(false)
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun updateNotification(progress: ExportProgress) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(progress))
    }

    private fun notification(progress: ExportProgress): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Cubical Compare")
            .setContentText("${progress.stage} • ${progress.detail}")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(progress.running)
            .setProgress(100, progress.percent, progress.running && progress.percent == 0)
        if (progress.running) {
            val cancel = PendingIntent.getService(
                this,
                1,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
        }
        return builder.build()
    }

    override fun onDestroy() {
        job?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "cubical_compare_export"
        private const val NOTIFICATION_ID = 207
        private const val ACTION_START = "io.github.retrofrost.cts.android.EXPORT"
        private const val ACTION_CANCEL = "io.github.retrofrost.cts.android.CANCEL_EXPORT"
        private const val EXTRA_PROJECT = "project"
        private const val EXTRA_URI = "uri"

        fun start(context: Context, project: StudioProject, destination: Uri) {
            val intent = Intent(context, ExportService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT, project.toJson())
                .putExtra(EXTRA_URI, destination.toString())
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ExportService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
