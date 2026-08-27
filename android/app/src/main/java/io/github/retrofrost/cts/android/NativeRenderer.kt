package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Native frame-addressable renderer for the 2.0.7 comparison contract.
 *
 * The timeline is expressed in the canonical 1920x1080/60 Hz reference
 * coordinates measured by the old renderer. Preview and export both call this
 * object, so there is no Python/preview/export drift.
 */
object NativeRenderer {
    private const val LOGICAL_W = 1920f
    private const val LOGICAL_H = 1080f
    private const val SLOT_PITCH = 476f
    private const val BODY_INSET = 9f
    private const val BODY_WIDTH = 471f

    private val openingStarts = intArrayOf(0, 120, 240, 360)
    private val openingEnds = intArrayOf(120, 240, 360, 528)
    private const val CONTINUOUS_START = 528
    private const val CONTINUOUS_STEP = 214
    private const val END_WIPE = 43
    private const val END_RISE = 11
    private const val END_HOLD = 268
    private const val FADE = 79
    private const val BLACK_TAIL = 8
    private const val OUTRO = END_WIPE + END_RISE + END_HOLD + FADE + BLACK_TAIL

    private val antiAlias = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
    private val extraBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun metadata(project: StudioProject): RenderMetadata {
        val fps = project.fps.coerceIn(1, 120)
        val duration = if (project.autoLength) {
            referenceTotalFrames(project.cards.size) / 60.0
        } else {
            project.customLengthSeconds.coerceAtLeast(1.0)
        }
        return RenderMetadata(
            frameCount = max(1, ceil(duration * fps).toInt()),
            duration = duration,
            fps = fps,
        )
    }

    fun renderBitmap(project: StudioProject, frame: Int, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width.coerceAtLeast(2), height.coerceAtLeast(2), Bitmap.Config.ARGB_8888).also {
            renderInto(project, frame, it)
        }

    fun renderInto(project: StudioProject, frame: Int, bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.scale(bitmap.width / LOGICAL_W, bitmap.height / LOGICAL_H)
        drawLogical(canvas, project, referenceFrame(project, frame))
        canvas.restore()
    }

    fun renderRgba(project: StudioProject, frame: Int, width: Int, height: Int): ByteArray {
        val bitmap = renderBitmap(project, frame, width, height)
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ByteArray(pixels.size * 4).also { out ->
                var offset = 0
                pixels.forEach { pixel ->
                    out[offset++] = Color.red(pixel).toByte()
                    out[offset++] = Color.green(pixel).toByte()
                    out[offset++] = Color.blue(pixel).toByte()
                    out[offset++] = Color.alpha(pixel).toByte()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawLogical(canvas: Canvas, project: StudioProject, referenceFrame: Double) {
        val cards = project.cards
        val contentEnd = referenceContentEnd(cards.size).toDouble()
        val totalEnd = contentEnd + OUTRO
        if (referenceFrame >= totalEnd - BLACK_TAIL) {
            canvas.drawColor(Color.BLACK)
            return
        }

        canvas.drawColor(Color.rgb(247, 247, 247))

        val scroll = if (referenceFrame <= CONTINUOUS_START) {
            0f
        } else {
            (((referenceFrame - CONTINUOUS_START) / CONTINUOUS_STEP) * SLOT_PITCH).toFloat()
        }

        cards.forEachIndexed { index, card ->
            val baseX = index * SLOT_PITCH - scroll
            if (baseX > LOGICAL_W + SLOT_PITCH || baseX + BODY_WIDTH < -SLOT_PITCH) return@forEachIndexed

            val start = cardStart(index).toDouble()
            if (referenceFrame < start) return@forEachIndexed

            val age = (referenceFrame - start).coerceAtLeast(0.0)
            val entry = easeOutCubic((age / if (index < 4) 42.0 else 34.0).coerceIn(0.0, 1.0)).toFloat()
            val cardY = if (index < 4) -LOGICAL_H * (1f - entry) else -72f * (1f - entry)
            drawCard(canvas, card, baseX + BODY_INSET, cardY, referenceFrame, start, index, project.showBadges)
        }

        if (referenceFrame >= contentEnd) {
            drawOutro(canvas, project, referenceFrame - contentEnd)
        }
    }

    private fun drawCard(
        canvas: Canvas,
        card: StudioCard,
        x: Float,
        y: Float,
        referenceFrame: Double,
        cardStart: Double,
        index: Int,
        showBadges: Boolean,
    ) {
        val titleHeight = if (card.title.isBlank()) 0f else 93f
        val descriptionHeight = if (card.description.isBlank()) 0f else 115f
        val imageHeight = (LOGICAL_H - titleHeight - descriptionHeight).coerceAtLeast(1f)
        val imageRect = RectF(x, y, x + BODY_WIDTH, y + imageHeight)

        drawArtwork(canvas, card, imageRect)

        var cursor = y + imageHeight
        if (titleHeight > 0f) {
            val titleRect = RectF(x, cursor, x + BODY_WIDTH, cursor + titleHeight)
            canvas.drawRect(titleRect, Paint().apply { color = Color.rgb(242, 242, 242) })
            drawFittedText(
                canvas = canvas,
                text = card.title,
                rect = titleRect,
                maxSize = 38f,
                minSize = 18f,
                color = Color.rgb(21, 21, 21),
                typeface = bold,
                maxLines = 2,
            )
            cursor += titleHeight
        }
        if (descriptionHeight > 0f) {
            val descriptionRect = RectF(x, cursor, x + BODY_WIDTH, cursor + descriptionHeight)
            canvas.drawRect(descriptionRect, Paint().apply { color = Color.rgb(99, 94, 87) })
            drawFittedText(
                canvas = canvas,
                text = card.description,
                rect = descriptionRect,
                maxSize = 25f,
                minSize = 14f,
                color = Color.WHITE,
                typeface = medium,
                maxLines = 3,
            )
        }

        if (showBadges && (card.value.isNotBlank() || card.badgeHeader.isNotBlank())) {
            val age = (referenceFrame - cardStart).coerceAtLeast(0.0)
            drawBadge(canvas, card, x, y, age, index)
        }
    }

    private fun drawArtwork(canvas: Canvas, card: StudioCard, destination: RectF) {
        val file = card.image.takeIf(String::isNotBlank)?.let(::File)
        val bitmap = if (file?.isFile == true) runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() else null
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            val paint = Paint().apply {
                shader = LinearGradient(
                    destination.left,
                    destination.top,
                    destination.left,
                    destination.bottom,
                    Color.rgb(19, 141, 219),
                    Color.rgb(11, 116, 190),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(destination, paint)
            return
        }

        try {
            val scale = max(destination.width() / bitmap.width, destination.height() / bitmap.height) * card.imageScale.coerceIn(0.25, 3.0).toFloat()
            val drawW = bitmap.width * scale
            val drawH = bitmap.height * scale
            val centreX = destination.centerX() + (card.imageX * destination.width()).toFloat()
            val centreY = destination.centerY() + (card.imageY * destination.height()).toFloat()
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(card.imageRotation.toFloat(), drawW / 2f, drawH / 2f)
                postTranslate(centreX - drawW / 2f, centreY - drawH / 2f)
            }
            canvas.save()
            canvas.clipRect(destination)
            canvas.drawBitmap(bitmap, matrix, Paint(antiAlias).apply { isFilterBitmap = true })
            canvas.restore()
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawBadge(canvas: Canvas, card: StudioCard, cardX: Float, cardY: Float, age: Double, index: Int) {
        val entry = easeOutCubic((age / 30.0).coerceIn(0.0, 1.0)).toFloat()
        val width = 286f
        val height = 330f
        val targetCx = cardX + BODY_WIDTH / 2f
        val targetCy = cardY + 218f
        val cx = if (index == 3) targetCx + (1f - entry) * 380f else targetCx
        val cy = targetCy - (1f - entry) * 185f
        val path = hexagon(cx, cy, width, height)

        canvas.drawPath(path, Paint(antiAlias).apply { color = Color.argb(218, 211, 9, 9) })
        canvas.drawPath(path, Paint(antiAlias).apply {
            color = Color.argb(145, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        })

        val shineT = ((age - 18.0) / 46.0).coerceIn(0.0, 1.0).toFloat()
        if (shineT in 0.001f..0.999f) {
            canvas.save()
            canvas.clipPath(path)
            val shineX = cx - width + shineT * width * 2.1f
            val shine = Path().apply {
                moveTo(shineX - 48f, cy - height)
                lineTo(shineX + 28f, cy - height)
                lineTo(shineX + 120f, cy + height)
                lineTo(shineX + 44f, cy + height)
                close()
            }
            canvas.drawPath(shine, Paint(antiAlias).apply { color = Color.argb(92, 255, 255, 255) })
            canvas.restore()
        }

        val header = card.badgeHeader.trim()
        val value = card.value.trim()
        if (header.isNotEmpty()) {
            drawCenteredSingleLine(canvas, header, cx, cy - 62f, 27f, 18f, Color.WHITE, medium, width - 48f)
            drawCenteredSingleLine(canvas, value, cx, cy + 27f, 55f, 26f, Color.WHITE, extraBold, width - 42f)
        } else {
            drawCenteredSingleLine(canvas, value, cx, cy + 2f, 58f, 26f, Color.WHITE, extraBold, width - 42f)
        }
    }

    private fun drawOutro(canvas: Canvas, project: StudioProject, age: Double) {
        val wipe = (age / END_WIPE).coerceIn(0.0, 1.0).toFloat()
        if (wipe > 0f) {
            canvas.drawRect(LOGICAL_W * (1f - wipe), 0f, LOGICAL_W, LOGICAL_H, Paint().apply { color = Color.rgb(20, 20, 22) })
        }
        if (age >= END_WIPE) {
            val riseAge = age - END_WIPE
            val rise = easeOutCubic((riseAge / END_RISE).coerceIn(0.0, 1.0)).toFloat()
            val panelTop = LOGICAL_H - rise * 300f
            canvas.drawRect(0f, panelTop, LOGICAL_W, LOGICAL_H, Paint().apply { color = Color.rgb(20, 20, 22) })
            drawCenteredSingleLine(
                canvas,
                project.name.ifBlank { "Cubical Compare" },
                LOGICAL_W / 2f,
                panelTop + 145f,
                68f,
                30f,
                Color.WHITE,
                extraBold,
                LOGICAL_W - 160f,
            )
        }
        val fadeStart = END_WIPE + END_RISE + END_HOLD
        if (age >= fadeStart) {
            val alpha = (((age - fadeStart) / FADE).coerceIn(0.0, 1.0) * 255).roundToInt()
            canvas.drawRect(0f, 0f, LOGICAL_W, LOGICAL_H, Paint().apply { color = Color.argb(alpha, 0, 0, 0) })
        }
    }

    private fun referenceFrame(project: StudioProject, outputFrame: Int): Double {
        val fps = project.fps.coerceIn(1, 120)
        val outputRef60 = outputFrame.coerceAtLeast(0) * 60.0 / fps
        if (project.autoLength) return outputRef60

        val contentEnd = referenceContentEnd(project.cards.size).toDouble()
        val sourceTotal = contentEnd + OUTRO
        val outputTotal = project.customLengthSeconds.coerceAtLeast(1.0) * 60.0
        if (outputTotal <= CONTINUOUS_START + OUTRO + 1.0) {
            return outputRef60 * sourceTotal / outputTotal
        }

        val outputOutroStart = outputTotal - OUTRO
        return when {
            outputRef60 <= CONTINUOUS_START -> outputRef60
            outputRef60 >= outputOutroStart -> contentEnd + (outputRef60 - outputOutroStart)
            contentEnd <= CONTINUOUS_START -> outputRef60
            else -> CONTINUOUS_START +
                (outputRef60 - CONTINUOUS_START) *
                (contentEnd - CONTINUOUS_START) /
                (outputOutroStart - CONTINUOUS_START)
        }
    }

    private fun referenceContentEnd(cardCount: Int): Int = when {
        cardCount <= 0 -> 0
        cardCount <= 4 -> openingEnds[cardCount - 1]
        else -> CONTINUOUS_START + (cardCount - 4) * CONTINUOUS_STEP
    }

    private fun referenceTotalFrames(cardCount: Int): Int = referenceContentEnd(cardCount) + OUTRO

    private fun cardStart(index: Int): Int = if (index < 4) openingStarts[index] else CONTINUOUS_START + (index - 4) * CONTINUOUS_STEP

    private fun easeOutCubic(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        val inverse = 1.0 - t
        return 1.0 - inverse * inverse * inverse
    }

    private fun hexagon(cx: Float, cy: Float, width: Float, height: Float): Path {
        val rx = width / 2f
        val ry = height / 2f
        return Path().apply {
            repeat(6) { index ->
                val angle = Math.toRadians((-90.0 + index * 60.0))
                val px = cx + cos(angle).toFloat() * rx
                val py = cy + sin(angle).toFloat() * ry
                if (index == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }

    private fun drawCenteredSingleLine(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        maxSize: Float,
        minSize: Float,
        color: Int,
        typeface: Typeface,
        maxWidth: Float,
    ) {
        if (text.isBlank()) return
        val paint = Paint(antiAlias).apply {
            this.color = color
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
            textSize = maxSize
        }
        while (paint.textSize > minSize && paint.measureText(text) > maxWidth) paint.textSize -= 1f
        val metrics = paint.fontMetrics
        canvas.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint)
    }

    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        rect: RectF,
        maxSize: Float,
        minSize: Float,
        color: Int,
        typeface: Typeface,
        maxLines: Int,
    ) {
        if (text.isBlank()) return
        val availableWidth = rect.width() - 30f
        var size = maxSize
        var lines: List<String>
        val paint = Paint(antiAlias).apply {
            this.color = color
            this.typeface = typeface
        }
        while (true) {
            paint.textSize = size
            lines = wrap(text, paint, availableWidth)
            val lineHeight = paint.fontSpacing
            if ((lines.size <= maxLines && lines.size * lineHeight <= rect.height() - 12f) || size <= minSize) break
            size -= 1f
        }
        lines = lines.take(maxLines)
        paint.textAlign = Paint.Align.CENTER
        val lineHeight = paint.fontSpacing
        val totalHeight = lineHeight * lines.size
        var baseline = rect.centerY() - totalHeight / 2f - paint.fontMetrics.ascent
        lines.forEach { line ->
            canvas.drawText(line, rect.centerX(), baseline, paint)
            baseline += lineHeight
        }
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && paint.measureText(candidate) > width) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
