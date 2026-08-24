package dev.infinitycomparison.cc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

internal object NativeArtwork {
    private const val titleHeight = 93
    private const val descriptionTop = 965
    const val badgeWidth = 325
    const val badgeHeight = 375
    const val badgeTop = 24f

    @Volatile private var extraBold: Typeface? = null
    @Volatile private var semiBold: Typeface? = null
    @Volatile private var medium: Typeface? = null

    fun body(context: Context, card: StudioCard): Bitmap {
        val bitmap = Bitmap.createBitmap(NativeTimeline.bodyWidth, NativeTimeline.bodyHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val title = card.title.trim().replace(Regex("\\s+"), " ")
        val description = card.description.trim().replace(Regex("\\s+"), " ")
        val titlePixels = if (title.isBlank()) 0 else titleHeight
        val descriptionPixels = if (description.isBlank()) 0 else NativeTimeline.bodyHeight - descriptionTop
        val imageHeight = NativeTimeline.bodyHeight - titlePixels - descriptionPixels

        if (card.imageLayer.lowercase() != "front") {
            drawArtwork(context, canvas, card, imageHeight)
        }
        if (titlePixels > 0) {
            val top = imageHeight.toFloat()
            canvas.drawRect(0f, top, NativeTimeline.bodyWidth.toFloat(), top + titlePixels, Paint().apply {
                color = Color.rgb(242, 242, 242)
            })
            drawFittedCentredText(
                canvas, title, RectF(12f, top + 2f, NativeTimeline.bodyWidth - 12f, top + titlePixels - 2f),
                46f, 27f, 2, font(context, "Poppins-Bold.ttf", true), Color.rgb(22, 22, 22),
            )
        }
        if (descriptionPixels > 0) {
            val top = (NativeTimeline.bodyHeight - descriptionPixels).toFloat()
            canvas.drawRect(0f, top, NativeTimeline.bodyWidth.toFloat(), NativeTimeline.bodyHeight.toFloat(), Paint().apply {
                color = Color.rgb(99, 94, 87)
            })
            drawFittedCentredText(
                canvas, description, RectF(17f, top + 5f, NativeTimeline.bodyWidth - 17f, NativeTimeline.bodyHeight - 5f),
                29f, 19f, 4, font(context, "Poppins-Medium.ttf", false), Color.rgb(250, 250, 248),
            )
        }
        val divider = Paint().apply { color = Color.rgb(15, 15, 15); strokeWidth = 2f }
        canvas.drawLine(0f, 0f, 0f, NativeTimeline.bodyHeight.toFloat(), divider)
        canvas.drawLine(NativeTimeline.bodyWidth - 1f, 0f, NativeTimeline.bodyWidth - 1f, NativeTimeline.bodyHeight.toFloat(), divider)
        return bitmap
    }

    fun frontArtwork(context: Context, card: StudioCard): Bitmap? {
        if (card.imageLayer.lowercase() != "front" || card.image.isBlank()) return null
        val titlePixels = if (card.title.isBlank()) 0 else titleHeight
        val descriptionPixels = if (card.description.isBlank()) 0 else NativeTimeline.bodyHeight - descriptionTop
        val imageHeight = NativeTimeline.bodyHeight - titlePixels - descriptionPixels
        return Bitmap.createBitmap(NativeTimeline.bodyWidth, imageHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
            drawArtwork(context, Canvas(it), card, imageHeight)
        }
    }

    fun badge(context: Context, card: StudioCard): Bitmap {
        val bitmap = Bitmap.createBitmap(badgeWidth, badgeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val face = Path().apply {
            moveTo(162.5f, 0f)
            lineTo(318f, 89f)
            lineTo(318f, 286f)
            lineTo(162.5f, 375f)
            lineTo(7f, 286f)
            lineTo(7f, 89f)
            close()
        }
        canvas.drawPath(face, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
            setShadowLayer(8f, 5f, 7f, color)
        })
        canvas.drawPath(face, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(194, 0, 12) })
        canvas.drawPath(face, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(158, 0, 8)
        })

        val words = card.value.trim().uppercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val header = card.badgeHeader.trim().uppercase().replace(Regex("\\s+"), " ")
        val lines = if (words.size <= 1) words else listOf(words.first(), words.drop(1).joinToString(" "))
        when {
            header.isNotBlank() && lines.size == 1 -> {
                drawBadgeLine(canvas, context, header, 72f, 31f, 262f, false)
                drawBadgeLine(canvas, context, lines.firstOrNull().orEmpty(), 218f, 78f, 278f, true)
            }
            header.isNotBlank() -> {
                drawBadgeLine(canvas, context, header, 70f, 31f, 262f, false)
                drawBadgeLine(canvas, context, lines.firstOrNull().orEmpty(), 181f, 88f, 278f, true)
                drawBadgeLine(canvas, context, lines.drop(1).joinToString(" "), 285f, 35f, 280f, false)
            }
            lines.size == 1 -> drawBadgeLine(canvas, context, lines[0], 181f, 91f, 280f, true)
            else -> {
                drawBadgeLine(canvas, context, lines[0], 143f, 91f, 280f, true)
                drawBadgeLine(canvas, context, lines[1], 238f, 42f, 285f, false)
            }
        }
        return bitmap
    }

    fun credits(context: Context, project: StudioProject): Bitmap {
        val bitmap = Bitmap.createBitmap(NativeTimeline.bodyWidth, NativeTimeline.bodyHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(28, 28, 29))
        drawCentred(canvas, "Values are estimates and may vary.", 75f, 23f, font(context, "Poppins-Medium.ttf", false), Color.WHITE)
        canvas.drawLine(50f, 232f, NativeTimeline.bodyWidth - 50f, 232f, Paint().apply { color = Color.LTGRAY; strokeWidth = 2f })
        drawCentred(canvas, "CREDITS", 310f, 48f, font(context, "Poppins-Medium.ttf", false), Color.WHITE)
        drawCentred(canvas, project.name.ifBlank { "Cubical Compare" }, 450f, 28f, font(context, "Poppins-SemiBold.ttf", true), Color.WHITE)
        drawCentred(canvas, "Created with", 550f, 22f, font(context, "Poppins-Bold.ttf", true), Color.WHITE)
        drawCentred(canvas, "Cubical Compare", 590f, 24f, font(context, "Poppins-Medium.ttf", false), Color.WHITE)
        drawCentred(canvas, "Design & Rendering", 690f, 22f, font(context, "Poppins-Bold.ttf", true), Color.WHITE)
        drawCentred(canvas, "Cubical", 730f, 24f, font(context, "Poppins-Medium.ttf", false), Color.WHITE)
        drawCentred(canvas, "CREDITS ARE OPTIONAL", 1035f, 13f, font(context, "Poppins-Medium.ttf", false), Color.LTGRAY)
        return bitmap
    }

    private fun drawArtwork(context: Context, canvas: Canvas, card: StudioCard, imageHeight: Int) {
        if (imageHeight <= 0) return
        val source = loadBitmap(context, card.image)
        if (source == null) {
            val paint = Paint().apply { color = Color.rgb(56, 56, 58) }
            canvas.drawRect(0f, 0f, NativeTimeline.bodyWidth.toFloat(), imageHeight.toFloat(), paint)
            return
        }
        canvas.save()
        canvas.clipRect(0f, 0f, NativeTimeline.bodyWidth.toFloat(), imageHeight.toFloat())
        val cropLeft = (source.width * card.imageCropLeft.coerceIn(0.0, 0.95)).toFloat()
        val cropTop = (source.height * card.imageCropTop.coerceIn(0.0, 0.95)).toFloat()
        val cropRight = (source.width * (1.0 - card.imageCropRight.coerceIn(0.0, 0.95))).toFloat()
        val cropBottom = (source.height * (1.0 - card.imageCropBottom.coerceIn(0.0, 0.95))).toFloat()
        val usableWidth = (cropRight - cropLeft).coerceAtLeast(1f)
        val usableHeight = (cropBottom - cropTop).coerceAtLeast(1f)
        val scale = max(NativeTimeline.bodyWidth / usableWidth, imageHeight / usableHeight) * card.imageScale.coerceIn(0.05, 20.0).toFloat()
        val matrix = Matrix().apply {
            postTranslate(-cropLeft - usableWidth / 2f, -cropTop - usableHeight / 2f)
            postScale(scale, scale)
            postRotate(card.imageRotation.toFloat())
            postTranslate(
                NativeTimeline.bodyWidth / 2f + card.imageX.toFloat(),
                imageHeight / 2f + card.imageY.toFloat(),
            )
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        source.recycle()
    }

    private fun loadBitmap(context: Context, value: String): Bitmap? {
        if (value.isBlank()) return null
        return runCatching {
            when {
                value.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(value)).use(BitmapFactory::decodeStream)
                value.startsWith("file://") -> BitmapFactory.decodeFile(Uri.parse(value).path)
                else -> BitmapFactory.decodeFile(value)
            }
        }.getOrNull()
    }

    private fun drawBadgeLine(canvas: Canvas, context: Context, text: String, centreY: Float, size: Float, maxWidth: Float, bold: Boolean) {
        if (text.isBlank()) return
        val base = font(context, if (bold) "Poppins-ExtraBold.ttf" else "Poppins-SemiBold.ttf", bold)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = base; textSize = size; color = Color.WHITE; textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 3f, 4f, Color.argb(110, 0, 0, 0))
        }
        while (paint.measureText(text) > maxWidth && paint.textSize > 17f) paint.textSize -= 1f
        val baseline = centreY - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, badgeWidth / 2f, baseline, paint)
    }

    private fun drawCentred(canvas: Canvas, text: String, y: Float, size: Float, typeface: Typeface, colour: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface; textSize = size; color = colour; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, NativeTimeline.bodyWidth / 2f, y - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun drawFittedCentredText(
        canvas: Canvas, text: String, bounds: RectF, maximum: Float, minimum: Float,
        maxLines: Int, typeface: Typeface, colour: Int,
    ) {
        var size = maximum
        var lines: List<String>
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; color = colour; textAlign = Paint.Align.CENTER }
        while (true) {
            paint.textSize = size
            lines = wrap(text, paint, bounds.width(), maxLines)
            val lineHeight = size * 1.12f
            if ((lines.size * lineHeight <= bounds.height() && lines.all { paint.measureText(it) <= bounds.width() }) || size <= minimum) break
            size -= 1f
        }
        val lineHeight = size * 1.12f
        var baseline = bounds.centerY() - lines.size * lineHeight / 2f - paint.ascent()
        for (line in lines) {
            canvas.drawText(line, bounds.centerX(), baseline, paint)
            baseline += lineHeight
        }
    }

    private fun wrap(text: String, paint: Paint, width: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(candidate) <= width || line.isBlank()) line = candidate
            else {
                result += line
                line = word
                if (result.size == maxLines - 1) break
            }
        }
        if (line.isNotBlank() && result.size < maxLines) result += line
        return result
    }

    private fun font(context: Context, filename: String, bold: Boolean): Typeface {
        val cached = when (filename) {
            "Poppins-ExtraBold.ttf" -> extraBold
            "Poppins-SemiBold.ttf" -> semiBold
            else -> medium
        }
        if (cached != null) return cached
        val loaded = runCatching { Typeface.createFromAsset(context.assets, "fonts/$filename") }
            .getOrElse { if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        when (filename) {
            "Poppins-ExtraBold.ttf" -> extraBold = loaded
            "Poppins-SemiBold.ttf" -> semiBold = loaded
            else -> medium = loaded
        }
        return loaded
    }
}

object NativeFrameRenderer {
    private const val maxCachedCards = 12
    private val bodies = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > maxCachedCards
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }
    private val badges = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > maxCachedCards
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }

    @Synchronized
    fun render(context: Context, project: StudioProject, frame: Int, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width.coerceAtLeast(2), height.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.scale(output.width / NativeTimeline.referenceWidth, output.height / NativeTimeline.referenceHeight)
        val alpha = NativeTimeline.sceneAlpha(project, frame)
        if (alpha > 0f) {
            val positions = NativeTimeline.positions(project, frame)
            val order = if (NativeTimeline.sceneFrame(project, frame) < NativeTimeline.continuousStart) {
                val active = positions.keys.maxOrNull()
                if (active == null) emptyList() else listOf(active) + positions.keys.filter { it != active }.sorted()
            } else positions.keys.sorted()
            val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt() }
            for (index in order) {
                val card = project.cards[index]
                val key = bodyKey(card)
                val body = bodies.getOrPut(key) { NativeArtwork.body(context, card) }
                canvas.drawBitmap(body, positions.getValue(index) + NativeTimeline.bodyInset, 0f, layerPaint)
            }
            NativeTimeline.creditsX(project, frame)?.let { x ->
                val credits = NativeArtwork.credits(context, project)
                canvas.drawBitmap(credits, x + NativeTimeline.bodyInset, 0f, layerPaint)
                credits.recycle()
            }
            val starts = NativeTimeline.cardStarts(project)
            for (index in positions.keys.sorted()) {
                val card = project.cards[index]
                if (!project.showBadges || card.value.isBlank()) continue
                val offset = NativeTimeline.badgeOffset(index, NativeTimeline.sceneFrame(project, frame) - starts[index]) ?: continue
                val key = badgeKey(card)
                val badge = badges.getOrPut(key) { NativeArtwork.badge(context, card) }
                val left = positions.getValue(index) + (NativeTimeline.slotPitch - NativeArtwork.badgeWidth) / 2f
                canvas.drawBitmap(badge, left, NativeArtwork.badgeTop + offset, layerPaint)
            }
            for (index in positions.keys.sorted()) {
                val front = NativeArtwork.frontArtwork(context, project.cards[index]) ?: continue
                canvas.drawBitmap(front, positions.getValue(index) + NativeTimeline.bodyInset, 0f, layerPaint)
                front.recycle()
            }
        }
        canvas.restore()
        return output
    }

    private fun bodyKey(card: StudioCard): String = "${card.id}:${card.hashCode()}"
    private fun badgeKey(card: StudioCard): String = "${card.id}:${card.badgeHeader}:${card.value}"
}
