package io.github.retrofrost.cts.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Creates three YouTube-ready stills from the exact shared renderer.
 *
 * The layout follows the broad WatchData thumbnail language: one strong
 * comparison frame, a short oversized headline, high contrast, and a compact
 * value callout. It deliberately does not change the video renderer itself.
 */
object ThumbnailGenerator {
    data class Thumbnail(val fileName: String, val jpeg: ByteArray)

    private const val WIDTH = 1280
    private const val HEIGHT = 720

    fun create(context: Context, project: StudioProject, baseName: String): List<Thumbnail> {
        val metadata = SharedRenderer.metadata(project)
        val last = (metadata.frameCount - 1).coerceAtLeast(0)
        val fractions = doubleArrayOf(0.16, 0.48, 0.78)
        val cardIndices = if (project.cards.isEmpty()) intArrayOf(0, 0, 0) else intArrayOf(
            0,
            project.cards.lastIndex / 2,
            max(0, project.cards.lastIndex - 1),
        )
        return fractions.mapIndexed { index, fraction ->
            val frame = (last * fraction).toInt().coerceIn(0, last)
            val source = SharedRenderer.render(project, frame, WIDTH, HEIGHT)
            try {
                val card = project.cards.getOrNull(cardIndices[index])
                val composed = compose(context, source, card?.title.orEmpty(), card?.value.orEmpty(), project.name)
                try {
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(composed.compress(Bitmap.CompressFormat.JPEG, 94, output))
                        output.toByteArray()
                    }
                    Thumbnail("$baseName - Thumbnail ${index + 1}.jpg", bytes)
                } finally {
                    composed.recycle()
                }
            } finally {
                source.recycle()
            }
        }
    }

    private fun compose(context: Context, source: Bitmap, cardTitle: String, value: String, projectName: String): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, WIDTH * 0.72f, 0f,
                intArrayOf(Color.argb(225, 8, 12, 24), Color.argb(160, 8, 12, 24), Color.TRANSPARENT),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), scrim)

        val rawHeadline = cardTitle.ifBlank { projectName }.ifBlank { "CUBICAL COMPARE" }
        val headline = shortHeadline(rawHeadline)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = thumbnailTypeface(context)
            textSize = 82f
            setShadowLayer(7f, 0f, 4f, Color.argb(190, 0, 0, 0))
        }
        val lines = wrapTwoLines(headline, textPaint, 700f)
        var y = if (lines.size == 1) 300f else 250f
        for (line in lines) {
            canvas.drawText(line, 62f, y, textPaint)
            y += 94f
        }

        if (value.isNotBlank()) {
            val label = value.trim().uppercase().take(28)
            val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = thumbnailTypeface(context)
                textSize = 38f
            }
            val w = valuePaint.measureText(label) + 56f
            val box = RectF(62f, y + 24f, 62f + w, y + 86f)
            val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 12, 18) }
            canvas.drawRoundRect(box, 20f, 20f, red)
            canvas.drawText(label, 90f, y + 69f, valuePaint)
        }
        return bitmap
    }

    private fun thumbnailTypeface(context: Context): Typeface = runCatching {
        Typeface.createFromAsset(context.assets, "fonts/Poppins-Bold.ttf")
    }.getOrElse {
        Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun shortHeadline(value: String): String {
        val words = value.replace('\n', ' ').trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.take(6).joinToString(" ").uppercase()
    }

    private fun wrapTwoLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(' ')
        if (paint.measureText(text) <= maxWidth || words.size <= 1) return listOf(text)
        var best = 1
        var bestDelta = Float.MAX_VALUE
        for (split in 1 until words.size) {
            val first = words.take(split).joinToString(" ")
            val second = words.drop(split).joinToString(" ")
            val firstWidth = paint.measureText(first)
            val secondWidth = paint.measureText(second)
            if (firstWidth <= maxWidth && secondWidth <= maxWidth) {
                val delta = kotlin.math.abs(firstWidth - secondWidth)
                if (delta < bestDelta) { best = split; bestDelta = delta }
            }
        }
        val first = words.take(best).joinToString(" ")
        val second = words.drop(best).joinToString(" ")
        if (paint.measureText(first) > maxWidth || paint.measureText(second) > maxWidth) {
            while (paint.textSize > 54f && (paint.measureText(first) > maxWidth || paint.measureText(second) > maxWidth)) {
                paint.textSize -= 2f
            }
        }
        return listOf(first, second)
    }
}
