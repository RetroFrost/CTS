package dev.thedataguys.cc

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var project = CompareProject.demo()
    private lateinit var status: TextView
    private lateinit var rendererLabel: TextView
    private lateinit var projectLabel: TextView
    private lateinit var preview: PreviewView
    private lateinit var renderButton: Button
    private lateinit var importRendererButton: Button
    private lateinit var importProjectButton: Button
    private val main = Handler(Looper.getMainLooper())
    private lateinit var rendererStore: RendererStore
    private lateinit var projectStore: ProjectStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rendererStore = RendererStore(this)
        projectStore = ProjectStore(this)
        RendererRuntime.activeSpec = rendererStore.active()
        project = projectStore.active()

        preview = PreviewView(this, project, RendererRuntime.activeSpec)
        projectLabel = infoText()
        rendererLabel = infoText()
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setPadding(24, 8, 24, 16)
            text = "Ready • native MediaCodec/EGL renderer"
        }

        renderButton = Button(this).apply {
            text = "Render MP4"
            setOnClickListener { runRender() }
        }
        importProjectButton = Button(this).apply {
            text = "Import project (.json / .csv)"
            setOnClickListener { chooseProject() }
        }
        val exportProject = Button(this).apply {
            text = "Export project JSON"
            setOnClickListener { chooseProjectExport() }
        }
        importRendererButton = Button(this).apply {
            text = "Import .renderer"
            setOnClickListener { chooseRenderer() }
        }
        val exportRenderer = Button(this).apply {
            text = "Export active .renderer"
            setOnClickListener { chooseRendererExport() }
        }
        val resetRenderer = Button(this).apply {
            text = "Reset renderer"
            setOnClickListener {
                val spec = rendererStore.reset()
                RendererRuntime.activeSpec = spec
                preview.setRenderer(spec)
                updateRendererLabel(spec)
                status.text = "Built-in renderer restored"
            }
        }
        val restartPreview = Button(this).apply {
            text = "Restart preview"
            setOnClickListener { preview.restart() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 18)
            addView(renderButton, fullWidth())
            addView(importProjectButton, fullWidth())
            addView(exportProject, fullWidth())
            addView(importRendererButton, fullWidth())
            addView(exportRenderer, fullWidth())
            addView(resetRenderer, fullWidth())
            addView(restartPreview, fullWidth())
        }

        val lower = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(projectLabel, fullWidth())
            addView(rendererLabel, fullWidth())
            addView(status, fullWidth())
            addView(controls, fullWidth())
        }
        val scroll = ScrollView(this).apply { addView(lower) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        updateProjectLabel(project)
        updateRendererLabel(RendererRuntime.activeSpec)
    }

    override fun onResume() {
        super.onResume()
        preview.running = true
        preview.invalidate()
    }

    override fun onPause() {
        preview.running = false
        super.onPause()
    }

    @Deprecated("Deprecated in Android; kept to avoid an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_IMPORT_RENDERER -> data?.data?.let(::importRenderer)
            REQUEST_EXPORT_RENDERER -> data?.data?.let(::exportRenderer)
            REQUEST_IMPORT_PROJECT -> data?.data?.let(::importProject)
            REQUEST_EXPORT_PROJECT -> data?.data?.let(::exportProject)
        }
    }

    private fun chooseRenderer() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
        }, REQUEST_IMPORT_RENDERER)
    }

    private fun chooseRendererExport() {
        val spec = rendererStore.active()
        val safeName = safeFileName(spec.name, "renderer")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "$safeName.renderer")
        }, REQUEST_EXPORT_RENDERER)
    }

    private fun chooseProject() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/csv", "text/plain", "application/octet-stream"))
        }, REQUEST_IMPORT_PROJECT)
    }

    private fun chooseProjectExport() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${safeFileName(project.title, "comparison")}.json")
        }, REQUEST_EXPORT_PROJECT)
    }

    private fun importRenderer(uri: Uri) {
        setBusy(true, "Validating renderer…")
        thread(name = "cc-renderer-import") {
            runCatching {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open renderer" }
                    rendererStore.import(input)
                }
            }.onSuccess { spec ->
                main.post {
                    RendererRuntime.activeSpec = spec
                    preview.setRenderer(spec)
                    preview.restart()
                    updateRendererLabel(spec)
                    setBusy(false, "Renderer imported and activated")
                }
            }.onFailure { error ->
                setBusy(false, "Import failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun exportRenderer(uri: Uri) {
        setBusy(true, "Writing renderer bundle…")
        thread(name = "cc-renderer-export") {
            runCatching {
                contentResolver.openOutputStream(uri, "w").use { output ->
                    rendererStore.writeActive(requireNotNull(output) { "Could not create renderer file" })
                }
            }.onSuccess { setBusy(false, "Active renderer exported") }
                .onFailure { error -> setBusy(false, "Renderer export failed: ${error.message ?: error.javaClass.simpleName}") }
        }
    }

    private fun importProject(uri: Uri) {
        setBusy(true, "Importing project…")
        thread(name = "cc-project-import") {
            runCatching {
                val name = displayName(uri) ?: "comparison.csv"
                contentResolver.openInputStream(uri).use { input ->
                    projectStore.import(requireNotNull(input) { "Could not open project" }, name)
                }
            }.onSuccess { imported ->
                main.post {
                    project = imported
                    preview.setProject(imported)
                    preview.restart()
                    updateProjectLabel(imported)
                    setBusy(false, "Project imported • ${imported.items.size} cards")
                }
            }.onFailure { error ->
                setBusy(false, "Project import failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun exportProject(uri: Uri) {
        setBusy(true, "Writing project JSON…")
        thread(name = "cc-project-export") {
            runCatching {
                contentResolver.openOutputStream(uri, "w").use { output ->
                    projectStore.export(project, requireNotNull(output) { "Could not create project file" })
                }
            }.onSuccess { setBusy(false, "Project exported") }
                .onFailure { error -> setBusy(false, "Project export failed: ${error.message ?: error.javaClass.simpleName}") }
        }
    }

    private fun runRender() {
        RendererRuntime.activeSpec = rendererStore.active()
        val renderProject = project
        setBusy(true, "Starting native MediaCodec export…")
        thread(name = "cc-native-render") {
            runCatching {
                val file = DirectMediaCodecRenderer(this).render(renderProject) { msg -> setStatus(msg) }
                file to MediaLibrary.publishVideo(this, file)
            }.onSuccess { (file, publishedPath) ->
                setBusy(false, "Saved ${file.name} • $publishedPath")
            }.onFailure { error ->
                setBusy(false, "Render failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private fun updateProjectLabel(value: CompareProject) {
        projectLabel.text = "Project: ${value.title} • ${value.items.size} cards • ${value.seconds}s • ${value.fps} fps • ${value.width}×${value.height}"
    }

    private fun updateRendererLabel(spec: RendererSpec) {
        rendererLabel.text = "Renderer: ${spec.name} • ${spec.author} • ${spec.id}"
    }

    private fun setBusy(busy: Boolean, text: String) {
        main.post {
            renderButton.isEnabled = !busy
            importRendererButton.isEnabled = !busy
            importProjectButton.isEnabled = !busy
            status.text = text
        }
    }

    private fun setStatus(text: String) {
        main.post { status.text = text }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun safeFileName(value: String, fallback: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { fallback }

    private fun infoText() = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setPadding(24, 10, 24, 4)
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val REQUEST_IMPORT_RENDERER = 1001
        private const val REQUEST_EXPORT_RENDERER = 1002
        private const val REQUEST_IMPORT_PROJECT = 1003
        private const val REQUEST_EXPORT_PROJECT = 1004
    }
}

class PreviewView(
    context: android.content.Context,
    project: CompareProject,
    rendererSpec: RendererSpec
) : View(context) {
    var running: Boolean = false
    private var project = project
    private var painter = ScenePainter(rendererSpec)
    private var frame = 0
    private var lastTickNanos = 0L

    fun setRenderer(spec: RendererSpec) {
        painter = ScenePainter(spec)
        invalidate()
    }

    fun setProject(value: CompareProject) {
        project = value
        frame = 0
        invalidate()
    }

    fun restart() {
        frame = 0
        lastTickNanos = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        painter.drawVideoFrame(canvas, project, frame)
        if (!running) return

        val now = System.nanoTime()
        val frameDuration = 1_000_000_000L / project.fps.coerceAtLeast(1)
        if (lastTickNanos == 0L || now - lastTickNanos >= frameDuration) {
            frame = (frame + 1) % (project.seconds * project.fps).coerceAtLeast(1)
            lastTickNanos = now
        }
        postInvalidateOnAnimation()
    }
}
