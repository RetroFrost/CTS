package dev.infinitycomparison.cc

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class CrashReportPayload(
    val timestamp: String,
    val reason: String,
    val versionName: String,
    val versionCode: Int,
    val device: String,
    val androidVersion: String,
    val processId: Int,
    val threadName: String,
    val memory: String,
    val lastState: String,
    val exception: String,
    val recentEvents: List<String>,
)

internal object CrashReportFormatter {
    fun format(payload: CrashReportPayload): String = buildString {
        appendLine("Cubical Compare automatic crash report")
        appendLine("======================================")
        appendLine("Time: ${payload.timestamp}")
        appendLine("Reason: ${payload.reason}")
        appendLine("App: ${payload.versionName} (${payload.versionCode})")
        appendLine("Device: ${payload.device}")
        appendLine("Android: ${payload.androidVersion}")
        appendLine("Process: ${payload.processId}")
        appendLine("Thread: ${payload.threadName}")
        appendLine("Memory: ${payload.memory}")
        appendLine("Last state: ${payload.lastState}")
        appendLine()
        appendLine("Exception")
        appendLine("---------")
        appendLine(payload.exception.trim())
        appendLine()
        appendLine("Recent app events")
        appendLine("-----------------")
        if (payload.recentEvents.isEmpty()) appendLine("No events were recorded.")
        else payload.recentEvents.forEach { appendLine(it) }
    }
}

/**
 * Keeps a small, private diagnostic journal without Logcat permissions. Managed crashes are
 * captured by the uncaught-exception handler; native/process deaths are inferred on the next
 * launch only when the editor was foregrounded or an export was active.
 */
object CrashJournal {
    private const val PREFS = "crash_journal"
    private const val KEY_FOREGROUND = "foreground"
    private const val KEY_EXPORT_ACTIVE = "export_active"
    private const val KEY_EXPORT_STAGE = "export_stage"
    private const val MAX_EVENT_BYTES = 48 * 1024
    private const val MAX_REPORT_BYTES = 192 * 1024

    private val lock = Any()
    private val handlingFatal = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null

    fun initialise(application: Application) {
        if (appContext != null) return
        appContext = application.applicationContext
        val context = application.applicationContext
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousForeground = preferences.getBoolean(KEY_FOREGROUND, false)
        val previousExport = preferences.getBoolean(KEY_EXPORT_ACTIVE, false)
        val previousStage = preferences.getString(KEY_EXPORT_STAGE, "Unknown") ?: "Unknown"

        installExceptionHandler()
        if (!reportFile(context).exists() && (previousForeground || previousExport)) {
            val state = when {
                previousExport -> "Export active: $previousStage"
                previousForeground -> "Editor in foreground"
                else -> "Unknown"
            }
            writeReport(
                reason = "The previous process ended unexpectedly",
                threadName = "Unavailable (the process ended before a managed stack trace was captured)",
                exception = "No managed exception was captured. The process may have been terminated by native code, the GPU driver, the encoder, Android, or a force-close. Process and memory values are measured during this recovery launch.",
                stateOverride = state,
            )
        }

        synchronized(lock) {
            eventsFile(context).apply {
                parentFile?.mkdirs()
                writeText("")
            }
        }
        preferences.edit()
            .putBoolean(KEY_FOREGROUND, false)
            .putBoolean(KEY_EXPORT_ACTIVE, false)
            .putString(KEY_EXPORT_STAGE, "")
            .commit()
        application.registerActivityLifecycleCallbacks(ForegroundTracker(context))
        record("Application process started")
    }

    fun record(message: String) {
        val context = appContext ?: return
        val safe = message.replace('\n', ' ').replace('\r', ' ').take(500)
        val line = "${now()}  $safe\n"
        synchronized(lock) {
            runCatching {
                val file = eventsFile(context)
                file.parentFile?.mkdirs()
                file.appendText(line)
                if (file.length() > MAX_EVENT_BYTES) {
                    val bytes = file.readBytes()
                    file.writeBytes(bytes.copyOfRange(bytes.size - MAX_EVENT_BYTES, bytes.size))
                }
            }
        }
    }

    fun setExportActive(active: Boolean, stage: String = "") {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_EXPORT_ACTIVE, active)
            .putString(KEY_EXPORT_STAGE, stage.take(300))
            .commit()
        record(if (active) "Export started: $stage" else "Export ended: $stage")
    }

    fun updateExportStage(stage: String, addEvent: Boolean = true) {
        val context = appContext ?: return
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_EXPORT_STAGE, null) == stage) return
        preferences.edit().putString(KEY_EXPORT_STAGE, stage.take(300)).commit()
        if (addEvent) record("Export stage: $stage")
    }

    fun copyPendingReportToClipboard(context: Context): Boolean {
        val file = reportFile(context.applicationContext)
        if (!file.isFile) return false
        return runCatching {
            val report = file.readText().takeLast(MAX_REPORT_BYTES)
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Cubical Compare crash log", report))
            file.delete()
            true
        }.getOrDefault(false)
    }

    private fun installExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (handlingFatal.compareAndSet(false, true)) {
                val stack = StringWriter().also { writer ->
                    error.printStackTrace(PrintWriter(writer))
                }.toString()
                writeReport(
                    reason = "Uncaught ${error.javaClass.name}",
                    threadName = thread.name,
                    exception = stack,
                )
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun writeReport(
        reason: String,
        threadName: String,
        exception: String,
        stateOverride: String? = null,
    ) {
        val context = appContext ?: return
        synchronized(lock) {
            runCatching {
                val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val state = stateOverride ?: when {
                    preferences.getBoolean(KEY_EXPORT_ACTIVE, false) ->
                        "Export active: ${preferences.getString(KEY_EXPORT_STAGE, "Unknown")}"
                    preferences.getBoolean(KEY_FOREGROUND, false) -> "Editor in foreground"
                    else -> "Background"
                }
                val runtime = Runtime.getRuntime()
                val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val maximum = runtime.maxMemory() / (1024 * 1024)
                val events = eventsFile(context).takeIf(File::isFile)
                    ?.readLines()
                    ?.takeLast(160)
                    .orEmpty()
                val report = CrashReportFormatter.format(
                    CrashReportPayload(
                        timestamp = now(),
                        reason = reason,
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        device = "${Build.MANUFACTURER} ${Build.MODEL}",
                        androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                        processId = Process.myPid(),
                        threadName = threadName,
                        memory = "${used} MiB used / ${maximum} MiB maximum heap",
                        lastState = state,
                        exception = exception,
                        recentEvents = events,
                    ),
                ).takeLast(MAX_REPORT_BYTES)
                reportFile(context).apply {
                    parentFile?.mkdirs()
                    writeText(report)
                }
            }
        }
    }

    private fun eventsFile(context: Context) = File(context.noBackupFilesDir, "diagnostics/events.log")
    private fun reportFile(context: Context) = File(context.noBackupFilesDir, "diagnostics/pending-crash.txt")

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.UK).format(Date())

    private class ForegroundTracker(private val context: Context) : Application.ActivityLifecycleCallbacks {
        private var startedActivities = 0

        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (startedActivities == 1) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_FOREGROUND, true)
                    .commit()
                record("Editor entered foreground")
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_FOREGROUND, false)
                    .commit()
                record("Editor entered background")
            }
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

class CubicalCompareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashJournal.initialise(this)
    }
}
