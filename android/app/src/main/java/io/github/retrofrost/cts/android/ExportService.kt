package io.github.retrofrost.cts.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

data class ExportProgress(
    val running: Boolean = false,
    val percent: Int = 0,
    val stage: String = "Ready",
    val detail: String = "",
)

object ExportState {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(ExportProgress())
    val state = mutable.asStateFlow()
    fun update(progress: ExportProgress) { mutable.value = progress }
}

class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var activeToken: String? = null
    @Volatile private var cancelRequestedToken: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Video exports", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val token = activeToken
            if (token != null) {
                cancelRequestedToken = token
                job?.cancel()
                val cancelling = ExportState.state.value.copy(stage = "Cancelling", detail = "Stopping safely")
                ExportState.update(cancelling)
                updateNotification(cancelling)
            }
            return START_NOT_STICKY
        }

        val snapshotPath = intent?.getStringExtra(EXTRA_SNAPSHOT)
        val destinationText = intent?.getStringExtra(EXTRA_URI)
        if (snapshotPath.isNullOrBlank() || destinationText.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val snapshotDir = File(snapshotPath)
        val token = snapshotDir.name
        activeToken = token
        cancelRequestedToken = null
        job?.cancel()

        val initial = ExportProgress(true, 0, "Preparing", "Starting the direct GPU renderer")
        ExportState.update(initial)
        startForeground(NOTIFICATION_ID, notification(initial))

        val localWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CubicalCompare:GpuExport:$token")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        job = scope.launch {
            fun ownsExport(): Boolean = activeToken == token
            fun cancelled(): Boolean = !ownsExport() || cancelRequestedToken == token
            fun publish(value: ExportProgress) {
                if (!ownsExport()) return
                ExportState.update(value)
                updateNotification(value)
            }

            try {
                val projectFile = File(snapshotDir, PROJECT_FILE)
                val rendererFile = File(snapshotDir, RENDERER_FILE)
                require(projectFile.isFile && rendererFile.isFile) { "The export snapshot is incomplete." }
                val project = StudioProject.fromJson(projectFile.readText())
                val spec = rendererFile.inputStream().use(RendererBundle::read)
                val exportProject = RendererBridge.resolveOutputProject(project, spec)
                val progress: (Int, String, String) -> Unit = { percent, stage, detail ->
                    publish(ExportProgress(true, percent.coerceIn(0, 100), stage, detail))
                }
                if (spec.precisionMode == "frame-exact" && Build.VERSION.SDK_INT < 29) {
                    HardwareVideoExporter(
                        context = applicationContext,
                        sourceProject = exportProject,
                        rendererSpec = spec,
                        shouldCancel = ::cancelled,
                        onProgress = progress,
                    ).export(Uri.parse(destinationText))
                } else {
                    DirectGpuVideoExporter(
                        context = applicationContext,
                        sourceProject = exportProject,
                        rendererSpec = spec,
                        shouldCancel = ::cancelled,
                        onProgress = progress,
                    ).export(Uri.parse(destinationText))
                }
                publish(ExportProgress(false, 100, "Finished", "The MP4 is ready"))
            } catch (_: CancellationException) {
                publish(ExportProgress(false, 0, "Cancelled", "Export cancelled"))
            } catch (error: Throwable) {
                publish(
                    ExportProgress(
                        false,
                        0,
                        "Export failed",
                        error.message ?: error::class.java.simpleName,
                    ),
                )
            } finally {
                if (localWakeLock.isHeld) localWakeLock.release()
                // Renderer v3 scene lookup is process-global. Re-register the real
                // active file before deleting the export snapshot it temporarily used.
                runCatching { RendererStore(applicationContext).active() }
                snapshotDir.deleteRecursively()
                if (activeToken == token) {
                    activeToken = null
                    cancelRequestedToken = null
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelfResult(startId)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeToken?.let { cancelRequestedToken = it }
        job?.cancel()
        val timedOut = ExportProgress(false, 0, "Export stopped", "Android ended the media-processing time window")
        ExportState.update(timedOut)
        updateNotification(timedOut)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    private fun updateNotification(progress: ExportProgress) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(progress))
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
        activeToken?.let { cancelRequestedToken = it }
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "cubical_compare_export"
        private const val NOTIFICATION_ID = 207
        private const val ACTION_START = "io.github.retrofrost.cts.android.EXPORT"
        private const val ACTION_CANCEL = "io.github.retrofrost.cts.android.CANCEL_EXPORT"
        private const val EXTRA_SNAPSHOT = "snapshot"
        private const val EXTRA_URI = "uri"
        private const val PROJECT_FILE = "project.json"
        private const val RENDERER_FILE = "renderer.renderer"

        fun start(context: Context, project: StudioProject, destination: Uri) {
            val queue = File(context.filesDir, "export-queue").apply { mkdirs() }
            val snapshot = File(queue, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                File(snapshot, PROJECT_FILE).writeText(project.toJson())
                File(snapshot, RENDERER_FILE).outputStream().use { RendererBundle.write(RendererRuntime.active, it) }
                val intent = Intent(context, ExportService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SNAPSHOT, snapshot.absolutePath)
                    .putExtra(EXTRA_URI, destination.toString())
                context.startForegroundService(intent)
            } catch (error: Throwable) {
                snapshot.deleteRecursively()
                throw error
            }
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ExportService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
