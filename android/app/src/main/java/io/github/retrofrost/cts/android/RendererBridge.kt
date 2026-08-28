package io.github.retrofrost.cts.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Native 2.0.7 renderer bridge. No Python, Chaquopy, Pillow or openpyxl. */
data class RenderMetadata(
    val frameCount: Int,
    val duration: Double,
    val fps: Int,
)

object RendererBridge {
    private val lock = Any()
    private val nativeRenderer = NativeFrameRenderer()
    private val ribbonRenderer = RibbonFrameRenderer()

    private fun ribbonActive(): Boolean = RibbonTimeline.isRibbon(RendererRuntime.active)

    fun metadata(project: StudioProject): RenderMetadata = synchronized(lock) {
        val fps = project.fps.coerceIn(1, 120)
        val spec = RendererRuntime.active
        val frameCount = if (ribbonActive()) {
            RibbonTimeline.totalFrameCount(project, spec)
        } else {
            NativeTimeline.totalFrameCount(project, spec)
        }.coerceAtLeast(1)
        RenderMetadata(frameCount, frameCount.toDouble() / fps, fps)
    }

    fun renderRgba(project: StudioProject, frame: Int, width: Int, height: Int): ByteArray = synchronized(lock) {
        if (ribbonActive()) {
            ribbonRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        } else {
            nativeRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        }
    }

    fun render(project: StudioProject, frame: Int, width: Int, height: Int): Bitmap = synchronized(lock) {
        if (ribbonActive()) {
            ribbonRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        } else {
            nativeRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        }
    }

    fun importData(project: StudioProject, path: String): StudioProject = synchronized(lock) {
        NativeImporters.importData(project, File(path))
    }

    fun importMegaPack(path: String, assets: File): StudioProject = synchronized(lock) {
        NativeImporters.importMegaPack(File(path), assets)
    }

    fun materialize(context: Context, uri: Uri, prefix: String): File {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty()
        val extension = displayName.substringAfterLast('.', "")
            .takeIf { it.length in 1..12 && it.all { char -> char.isLetterOrDigit() } }
            ?.let { ".$it" }
            ?: when (context.contentResolver.getType(uri)) {
                "text/csv" -> ".csv"
                "text/tab-separated-values" -> ".tsv"
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
                "application/vnd.ms-excel.sheet.macroEnabled.12" -> ".xlsm"
                "application/zip" -> ".zip"
                else -> ".bin"
            }
        val destination = File(imports, "$prefix-${System.nanoTime()}$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected file could not be opened." }
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }
}
