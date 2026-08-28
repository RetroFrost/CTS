package dev.thedataguys.cc

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaLibrary {
    fun publishVideo(context: Context, source: File): String {
        if (Build.VERSION.SDK_INT < 29) return source.absolutePath
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Cubical Compare")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
            "Could not create MediaStore video"
        }
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "Could not open MediaStore output" }
                source.inputStream().use { it.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Movies/Cubical Compare/${source.name}"
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
