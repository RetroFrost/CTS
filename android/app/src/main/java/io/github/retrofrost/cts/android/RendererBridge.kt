package io.github.retrofrost.cts.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/**
 * Compatibility facade retained so the complete 2.0.7 Compose studio can stay
 * unchanged while every implementation underneath it is native Kotlin.
 */
object RendererBridge {
    fun metadata(project: StudioProject): RenderMetadata = NativeRenderer.metadata(project)

    fun renderRgba(project: StudioProject, frame: Int, width: Int, height: Int): ByteArray =
        NativeRenderer.renderRgba(project, frame, width, height)

    fun render(project: StudioProject, frame: Int, width: Int, height: Int): Bitmap =
        NativeRenderer.renderBitmap(project, frame, width, height)

    fun importData(project: StudioProject, path: String): StudioProject {
        val source = File(path)
        val cards = NativeSpreadsheetImporter.load(source.absolutePath)
        return project.copy(
            name = source.nameWithoutExtension.replace('_', ' ').trim().ifBlank { project.name },
            cards = cards,
        )
    }

    fun importMegaPack(path: String, assets: File): StudioProject =
        NativeMegaPackImporter.load(path, assets)

    fun materialize(context: Context, uri: Uri, prefix: String): File {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val name = uri.lastPathSegment.orEmpty().substringAfterLast('/').takeIf(String::isNotBlank)
        val suffix = name?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 12 }?.let { ".$it" }
            ?: context.contentResolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.length <= 12 }?.let { ".$it" }
            ?: ".bin"
        val destination = File(imports, "$prefix-${System.nanoTime()}$suffix")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected file could not be opened." }
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }
}
