package dev.infinitycomparison.cc

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val project = CompareProject.demo()
    private lateinit var status: TextView
    private lateinit var preview: PreviewView
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preview = PreviewView(this, project)
        status = TextView(this).apply {
            text = "Native rewrite ready • no Python • direct MediaCodec export"
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(22, 18, 22, 18)
        }

        val render = Button(this).apply {
            text = "Export MP4 directly"
            setOnClickListener { runRender() }
        }
        val thumbJpeg = Button(this).apply {
            text = "Create CTR thumbnail JPG"
            setOnClickListener { runThumbnail(ThumbnailFormat.JPEG) }
        }
        val thumbPng = Button(this).apply {
            text = "Create CTR thumbnail PNG"
            setOnClickListener { runThumbnail(ThumbnailFormat.PNG) }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 14, 24, 20)
            addView(render, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(thumbJpeg, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(thumbPng, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(preview, LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(buttons, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        setContentView(root)
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

    private fun runRender() {
        status.text = "Starting native MediaCodec export…"
        thread(name = "cc-native-render") {
            runCatching {
                DirectMediaCodecRenderer(this).render(project) { msg -> setStatus(msg) }
            }.onSuccess { file ->
                setStatus("MP4 saved: ${file.cleanPath()}")
            }.onFailure { error ->
                setStatus("Render failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private fun runThumbnail(format: ThumbnailFormat) {
        status.text = "Creating curiosity thumbnail…"
        thread(name = "cc-thumbnail") {
            runCatching {
                ThumbnailExporter(this).exportCuriosityThumbnail(project, format)
            }.onSuccess { file ->
                setStatus("Thumbnail saved: ${file.cleanPath()}")
            }.onFailure { error ->
                setStatus("Thumbnail failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private fun setStatus(text: String) {
        main.post { status.text = text }
    }

    private fun File.cleanPath(): String = absolutePath.substringAfter("/Android/data/")
}

class PreviewView(
    context: android.content.Context,
    private val project: CompareProject
) : View(context) {
    var running: Boolean = false
    private val painter = ScenePainter()
    private var frame = 0

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        painter.drawVideoFrame(canvas, project, frame)
        frame = (frame + 1) % (project.seconds * project.fps)
        if (running) postInvalidateDelayed(33L)
    }
}
