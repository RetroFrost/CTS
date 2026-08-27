package io.github.retrofrost.cts.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

data class GeneratedThumbnail(val fileName: String, val jpeg: ByteArray)

/** Truthful CTR-oriented thumbnails generated from the same native renderer. */
object NativeThumbnailGenerator {
    fun create(project: StudioProject, baseName: String): List<GeneratedThumbnail> {
        val metadata = NativeRenderer.metadata(project)
        val fractions = doubleArrayOf(0.16, 0.50, 0.82)
        return fractions.mapIndexed { index, fraction ->
            val frame = ((metadata.frameCount - 1).coerceAtLeast(0) * fraction).roundToInt()
            val bitmap = NativeRenderer.renderBitmap(project, frame, 1280, 720)
            try {
                composeOverlay(bitmap, project, index)
                val bytes = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)
                    output.toByteArray()
                }
                GeneratedThumbnail("$baseName - Thumbnail ${index + 1}.jpg", bytes)
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun saveToGallery(context: Context, thumbnails: List<GeneratedThumbnail>): List<String> {
        val saved = mutableListOf<String>()
        thumbnails.forEach { thumbnail ->
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, thumbnail.fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Cubical Compare")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create ${thumbnail.fileName} in Pictures.")
                try {
                    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(thumbnail.jpeg) }
                        ?: error("Could not write ${thumbnail.fileName}.")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    saved += thumbnail.fileName
                } catch (error: Throwable) {
                    context.contentResolver.delete(uri, null, null)
                    throw error
                }
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Cubical Compare").apply { mkdirs() }
                val file = File(directory, thumbnail.fileName)
                FileOutputStream(file).use { it.write(thumbnail.jpeg) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                saved += file.name
            }
        }
        return saved
    }

    private fun composeOverlay(bitmap: Bitmap, project: StudioProject, variant: Int) {
        val canvas = Canvas(bitmap)
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        canvas.drawRect(
            0f,
            0f,
            width,
            height,
            Paint().apply {
                shader = LinearGradient(0f, height * 0.35f, 0f, height, Color.TRANSPARENT, Color.argb(220, 0, 0, 0), Shader.TileMode.CLAMP)
            },
        )

        val card = when (variant) {
            0 -> project.cards.firstOrNull()
            1 -> project.cards.getOrNull(project.cards.size / 2)
            else -> project.cards.lastOrNull()
        }
        val headline = when (variant) {
            0 -> project.name.ifBlank { "Cubical Compare" }
            1 -> card?.title?.ifBlank { project.name }.orEmpty()
            else -> {
                val first = project.cards.firstOrNull()?.title.orEmpty()
                val last = project.cards.lastOrNull()?.title.orEmpty()
                if (first.isNotBlank() && last.isNotBlank()) "$first  VS  $last" else project.name
            }
        }
        val subline = card?.value.orEmpty().ifBlank { card?.description.orEmpty() }.take(72)
        drawTextFit(canvas, headline, RectF(54f, height - 230f, width - 54f, height - 118f), 64f, 30f, Color.WHITE)
        if (subline.isNotBlank()) {
            drawTextFit(canvas, subline, RectF(58f, height - 112f, width - 58f, height - 48f), 38f, 22f, Color.rgb(255, 220, 220))
        }
    }

    private fun drawTextFit(canvas: Canvas, text: String, rect: RectF, max: Float, min: Float, color: Int) {
        if (text.isBlank()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            this.color = color
            textSize = max
        }
        while (paint.textSize > min && paint.measureText(text) > rect.width()) paint.textSize -= 1f
        val y = rect.centerY() - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(text, rect.left, y, paint)
    }
}
