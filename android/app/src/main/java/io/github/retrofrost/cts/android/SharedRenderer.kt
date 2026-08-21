package io.github.retrofrost.cts.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File

object SharedRenderer {
    private val lock = Any()
    private val module by lazy { Python.getInstance().getModule("cts_android_bridge") }

    fun metadata(project: StudioProject): RenderMetadata = synchronized(lock) {
        val json = JSONObject(module.callAttr("metadata", project.toJson()).toString())
        RenderMetadata(json.getInt("frame_count"), json.getDouble("duration"), json.getInt("fps"))
    }

    /**
     * Starts a long-lived export render session in Python.
     *
     * The old exporter serialized and reparsed the entire StudioProject for every
     * single frame. A 9,500-frame export therefore rebuilt the same Python model
     * 9,500 times. The export session parses it once and reuses that object until
     * [endVideoExport] is called.
     */
    fun beginVideoExport(project: StudioProject): RenderMetadata = synchronized(lock) {
        val json = JSONObject(module.callAttr("begin_export", project.toJson()).toString())
        RenderMetadata(json.getInt("frame_count"), json.getDouble("duration"), json.getInt("fps"))
    }

    /** Returns encoder-ready YUV420 bytes directly from the Python renderer. */
    fun renderYuv420(frame: Int, width: Int, height: Int, semiPlanar: Boolean): ByteArray = synchronized(lock) {
        val raw = module.callAttr("render_yuv420", frame, width, height, semiPlanar).toJava(ByteArray::class.java)
        val expected = width * height * 3 / 2
        require(raw.size == expected) {
            "Shared renderer returned an invalid YUV frame buffer: ${raw.size}, expected $expected."
        }
        raw
    }

    fun endVideoExport() {
        synchronized(lock) {
            // Cleanup must never hide the original encoder/rendering exception.
            runCatching { module.callAttr("end_export") }
        }
    }

    fun render(project: StudioProject, frame: Int, width: Int, height: Int): Bitmap = synchronized(lock) {
        val raw = module.callAttr("render_rgba", project.toJson(), frame, width, height).toJava(ByteArray::class.java)
        require(raw.size == width * height * 4) { "Shared renderer returned an invalid frame buffer." }
        val pixels = IntArray(width * height)
        var src = 0
        for (i in pixels.indices) {
            val r = raw[src++].toInt() and 0xff; val g = raw[src++].toInt() and 0xff
            val b = raw[src++].toInt() and 0xff; val a = raw[src++].toInt() and 0xff
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun importData(project: StudioProject, path: String): StudioProject = synchronized(lock) {
        StudioProject.fromJson(module.callAttr("import_data", project.toJson(), path).toString())
    }

    fun importMegaPack(path: String, assets: File): StudioProject = synchronized(lock) {
        assets.mkdirs(); StudioProject.fromJson(module.callAttr("import_pack", path, assets.absolutePath).toString())
    }

    fun materialize(context: Context, uri: Uri, prefix: String): File {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "input.bin"
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(File(context.cacheDir, "imports").apply { mkdirs() }, "$prefix-${System.nanoTime()}-$safe")
        context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input); file.outputStream().use { output -> input.copyTo(output, 1024 * 1024) } }
        return file
    }
}
