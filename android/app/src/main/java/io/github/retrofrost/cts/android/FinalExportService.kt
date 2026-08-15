package io.github.retrofrost.cts.android

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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExportProgress(val running: Boolean = false, val percent: Int = 0, val stage: String = "Idle", val detail: String = "")

object FinalExportState {
    private val mutable = MutableStateFlow(ExportProgress())
    val state = mutable.asStateFlow()
    internal fun update(value: ExportProgress) { mutable.value = value }
}

class FinalExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cubical Compare exports", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            FinalExportState.update(FinalExportState.state.value.copy(stage = "Canceling", detail = "Stopping after the current encoder operation"))
            return START_NOT_STICKY
        }
        val json = intent?.getStringExtra(EXTRA_PROJECT) ?: return START_NOT_STICKY
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return START_NOT_STICKY
        cancelled = false
        startForeground(NOTIFICATION_ID, notification(0, "Preparing export"))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CubicalCompare:FinalExport")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        FinalExportState.update(ExportProgress(true, 0, "Preparing", "Starting the shared renderer"))
        scope.launch {
            try {
                val project = StudioProject.fromJson(json)
                FinalExportEngine(applicationContext, project, { cancelled }) { percent, stage, detail ->
                    FinalExportState.update(ExportProgress(true, percent, stage, detail))
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(percent, "$stage · $detail"))
                }.export(uri)
                FinalExportState.update(ExportProgress(false, 100, "Finished", "MP4 saved"))
            } catch (_: CancellationException) {
                FinalExportState.update(ExportProgress(false, 0, "Canceled", "Export canceled"))
            } catch (e: Throwable) {
                FinalExportState.update(ExportProgress(false, 0, "Export failed", e.message ?: e.javaClass.simpleName))
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(percent: Int, text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Cubical Compare")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(percent < 100)
        .setProgress(100, percent.coerceIn(0, 100), false)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
            PendingIntent.getService(this, 1, Intent(this, FinalExportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    override fun onDestroy() {
        cancelled = true
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "cubical_compare_export"
        private const val NOTIFICATION_ID = 2200
        private const val ACTION_START = "io.github.retrofrost.cts.android.EXPORT"
        private const val ACTION_CANCEL = "io.github.retrofrost.cts.android.CANCEL_EXPORT"
        private const val EXTRA_PROJECT = "project"
        private const val EXTRA_URI = "uri"

        fun start(context: Context, projectJson: String, destination: Uri) {
            ContextCompat.startForegroundService(context,
                Intent(context, FinalExportService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_PROJECT, projectJson).putExtra(EXTRA_URI, destination.toString()))
        }
        fun cancel(context: Context) { context.startService(Intent(context, FinalExportService::class.java).setAction(ACTION_CANCEL)) }
    }
}
