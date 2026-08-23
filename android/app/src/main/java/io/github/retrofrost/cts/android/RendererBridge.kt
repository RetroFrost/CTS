package io.github.retrofrost.cts.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

data class RenderMetadata(
    val frameCount: Int,
    val duration: Double,
    val fps: Int,
)

object RendererBridge {
    private val lock = Any()
    private val bridge by lazy { Python.getInstance().getModule("cts_android_bridge") }

    fun metadata(project: StudioProject): RenderMetadata = synchronized(lock) {
        val json = JSONObject(bridge.callAttr("metadata", project.toJson()).toString())
        RenderMetadata(
            frameCount = json.getInt("frame_count"),
            duration = json.getDouble("duration"),
            fps = json.getInt("fps"),
        )
    }

    fun renderRgba(project: StudioProject, frame: Int, width: Int, height: Int): ByteArray =
        synchronized(lock) {
            bridge.callAttr("render_rgba", project.toJson(), frame, width, height)
                .toJava(ByteArray::class.java)
        }

    fun render(project: StudioProject, frame: Int, width: Int, height: Int): Bitmap {
        val bytes = renderRgba(project, frame, width, height)
        return createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        }
    }

    fun importData(project: StudioProject, path: String): StudioProject = synchronized(lock) {
        StudioProject.fromJson(
            bridge.callAttr("import_data", project.toJson(), path).toString(),
        ).copyUiSettingsFrom(project)
    }

    fun importMegaPack(path: String, assets: File): StudioProject = synchronized(lock) {
        StudioProject.fromJson(
            bridge.callAttr("import_pack", path, assets.absolutePath).toString(),
        )
    }

    fun materialize(context: Context, uri: Uri, prefix: String): File {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val suffix = context.contentResolver.getType(uri)?.substringAfterLast('/')?.let { ".$it" }
            ?: ".bin"
        val destination = File(imports, "$prefix-${System.nanoTime()}$suffix")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected file could not be opened." }
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }
}
