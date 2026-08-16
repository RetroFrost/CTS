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
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private var activeJob: Job? = null

    private val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cubical Compare background exports", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            preferences.edit().clear().apply()
            FinalExportState.update(FinalExportState.state.value.copy(stage = "Canceling", detail = "Stopping after the current encoder operation"))
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(FinalExportState.state.value.percent, "Canceling export…", true),
            )
            return START_NOT_STICKY
        }

        if (activeJob?.isActive == true) return START_REDELIVER_INTENT

        val json = intent?.getStringExtra(EXTRA_PROJECT)
            ?: preferences.getString(PREF_PROJECT, null)
            ?: return stopMissingRequest(startId)
        val uriText = intent?.getStringExtra(EXTRA_URI)
            ?: preferences.getString(PREF_URI, null)
            ?: return stopMissingRequest(startId)
        val folderUri = Uri.parse(uriText)

        preferences.edit().putString(PREF_PROJECT, json).putString(PREF_URI, uriText).apply()
        cancelled = false
        startForeground(NOTIFICATION_ID, notification(0, "Preparing background export", true))
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CubicalCompare:BackgroundExport")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        }
        FinalExportState.update(ExportProgress(true, 0, "Preparing", "Background renderer is starting"))

        activeJob = scope.launch {
            try {
                val project = StudioProject.fromJson(json)
                val folder = DocumentFile.fromTreeUri(applicationContext, folderUri)
                    ?: error("The selected export folder could not be opened.")
                require(folder.canWrite()) { "The selected export folder is not writable." }
                val baseName = exportBaseName(project)
                val video = replaceDocument(folder, "video/mp4", "$baseName.mp4")
                FinalExportEngine(applicationContext, project, { cancelled }) { percent, stage, detail ->
                    val mapped = (percent.coerceIn(0, 100) * 94 / 100).coerceIn(0, 94)
                    FinalExportState.update(ExportProgress(true, mapped, stage, detail))
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification(mapped, "$stage · $detail", true),
                    )
                }.export(video.uri)

                if (cancelled) throw CancellationException("Export cancelled")
                FinalExportState.update(ExportProgress(true, 95, "Thumbnails", "Creating WatchData-inspired thumbnails"))
                val thumbnails = ThumbnailGenerator.create(project, baseName)
                thumbnails.forEachIndexed { index, thumbnail ->
                    if (cancelled) throw CancellationException("Export cancelled")
                    val document = replaceDocument(folder, "image/jpeg", thumbnail.fileName)
                    contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                        output.write(thumbnail.jpeg)
                    } ?: error("Could not write ${thumbnail.fileName}.")
                    val progress = 96 + index
                    FinalExportState.update(ExportProgress(true, progress, "Thumbnails", "Saved ${thumbnail.fileName}"))
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification(progress, "Thumbnails · ${index + 1} / ${thumbnails.size}", true),
                    )
                }
                preferences.edit().clear().apply()
                FinalExportState.update(ExportProgress(false, 100, "Finished", "MP4 + ${thumbnails.size} thumbnails saved"))
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(100, "Export finished · MP4 + ${thumbnails.size} thumbnails saved", false),
                )
            } catch (_: CancellationException) {
                preferences.edit().clear().apply()
                FinalExportState.update(ExportProgress(false, 0, "Canceled", "Export canceled"))
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(0, "Export canceled", false, indeterminate = false),
                )
            } catch (error: Throwable) {
                preferences.edit().clear().apply()
                val detail = error.message ?: error.javaClass.simpleName
                FinalExportState.update(ExportProgress(false, 0, "Export failed", detail))
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(0, "Export failed · $detail", false, indeterminate = false),
                )
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                activeJob = null
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun replaceDocument(folder: DocumentFile, mime: String, name: String): DocumentFile {
        folder.findFile(name)?.delete()
        return requireNotNull(folder.createFile(mime, name)) { "Could not create $name in the selected folder." }
    }

    private fun exportBaseName(project: StudioProject): String {
        val raw = project.name.trim().takeIf { it.isNotBlank() && !it.equals("Untitled", true) }
            ?: "Cubical-Compare-2.0.5"
        val safe = raw.replace(Regex("[\\/:*?\"<>|]"), "-").replace(Regex("\s+"), " ").trim().take(96)
        return safe.ifBlank { "Cubical-Compare-2.0.5" }
    }

    private fun stopMissingRequest(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun notification(
        percent: Int,
        text: String,
        ongoing: Boolean,
        indeterminate: Boolean = percent <= 0 && ongoing,
    ) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Cubical Compare")
        .setContentText(text)
        .setOnlyAlertOnce(ongoing)
        .setOngoing(ongoing)
        .setProgress(100, percent.coerceIn(0, 100), indeterminate)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .apply {
            if (ongoing) {
                addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    PendingIntent.getService(
                        this@FinalExportService,
                        1,
                        Intent(this@FinalExportService, FinalExportService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
        }
        .build()

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately do not stop: export is a user-requested long-running
        // media-processing task and must continue after the editor is swiped away.
        super.onTaskRemoved(rootIntent)
    }

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
        private const val PREFS = "background_export"
        private const val PREF_PROJECT = "project"
        private const val PREF_URI = "uri"

        fun start(context: Context, projectJson: String, destination: Uri) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FinalExportService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PROJECT, projectJson)
                    .putExtra(EXTRA_URI, destination.toString()),
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, FinalExportService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
