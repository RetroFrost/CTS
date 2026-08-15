package io.github.retrofrost.cts.android.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.util.LinkedHashMap
import kotlin.math.max

/** Bounded image repository shared by every drawing pass in one render session. */
internal class BitmapSourceCache(
    private val context: Context,
    private val maximumEntries: Int = 6,
) : AutoCloseable {
    private val missing = mutableSetOf<String>()
    private val bitmaps = object : LinkedHashMap<String, Bitmap>(maximumEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > maximumEntries
            if (remove) eldest?.value?.takeUnless(Bitmap::isRecycled)?.recycle()
            return remove
        }
    }

    fun load(source: String?): Bitmap? {
        val key = source?.trim().orEmpty()
        if (key.isBlank() || key in missing) return null
        bitmaps[key]?.let { cached ->
            if (!cached.isRecycled) return cached
            bitmaps.remove(key)
        }
        val decoded = runCatching { decode(key) }.getOrNull()
        if (decoded == null) missing += key else bitmaps[key] = decoded
        return decoded
    }

    override fun close() {
        bitmaps.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        bitmaps.clear()
        missing.clear()
    }

    private fun decode(key: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(key)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable image bounds" }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DECODED_EDGE) {
            sampleSize *= 2
        }
        return open(key)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } ?: error("Could not decode image")
    }

    private fun open(key: String): InputStream? = when {
        key.startsWith("http://", true) || key.startsWith("https://", true) ->
            URL(key).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "CTS-Android-Renderer")
            }.getInputStream()
        key.startsWith("content://", true) || key.startsWith("file://", true) ->
            context.contentResolver.openInputStream(Uri.parse(key))
        else -> FileInputStream(File(key))
    }

    private companion object {
        // A 1920x1080 reference card is only 480 px wide. This still leaves ample
        // supersampling while preventing a handful of phone photos from exhausting
        // the export worker's heap.
        const val MAX_DECODED_EDGE = 1_536
    }
}
