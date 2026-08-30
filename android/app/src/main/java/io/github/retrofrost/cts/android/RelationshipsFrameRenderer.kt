package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Native interpreter for the measured Types Of Relationships reference renderer. */
class RelationshipsFrameRenderer {
    private val imageCache = object : LinkedHashMap<String, Bitmap>(10, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > 10
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    @Synchronized
    fun render(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): Bitmap {
        val reference = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        drawReference(Canvas(reference), project, frameIndex.coerceAtLeast(0), RendererRuntime.active)
        if (outputWidth == 1920 && outputHeight == 1080) return reference
        val output = Bitmap.createBitmap(outputWidth.coerceAtLeast(2), outputHeight.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(reference, Rect(0, 0, 1920, 1080), Rect(0, 0, output.width, output.height), paint)
        reference.recycle()
        return output
    }

    @Synchronized
    fun renderRgba(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): ByteArray {
        val bitmap = render(project, frameIndex, outputWidth, outputHeight)
        return try {
            ByteArray(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawReference(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        canvas.drawColor(spec.backgroundColor)
        if (project.cards.isEmpty()) {
            drawIntroLogo(canvas, frame, spec)
            return
        }
        val contentEnd = RelationshipsTimeline.contentEndFrame(project, spec)
        when {
            frame < spec.openingStarts.firstOrNull().orZero() -> drawIntroLogo(canvas, frame, spec)
            frame < contentEnd -> drawContent(canvas, project, frame, spec)
            else -> drawOutro(canvas, project, frame, contentEnd, spec)
        }
    }

    private fun drawIntroLogo(canvas: Canvas, frame: Int, spec: RendererSpec) {
        val fadeIn = smooth((frame / 36f).coerceIn(0f, 1f))
        val settle = when {
            frame < 90 -> 1.42f - 0.46f * smooth(frame / 90f)
            else -> 0.96f + 0.04f * smooth(((180 - frame).coerceAtLeast(0)) / 90f)
        }
        val alpha = if (frame > 340) ((384 - frame) / 44f).coerceIn(0f, 1f) else 1f
        val cx = 960f
        val cy = 470f
        canvas.save()
        canvas.scale(settle, settle, cx, cy)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 9f
        paint.strokeCap = Paint.Cap.ROUND
        paint.alpha = (255 * fadeIn * alpha).roundToInt().coerceIn(0, 255)
        val left = RectF(cx - 250f, cy - 118f, cx - 5f, cy + 118f)
        val right = RectF(cx + 5f, cy - 118f, cx + 250f, cy + 118f)
        paint.color = Color.rgb(216, 235, 42)
        canvas.drawArc(left, 42f, 276f, false, paint)
        paint.color = Color.rgb(238, 111, 139)
        canvas.drawArc(right, 222f, 276f, false, paint)
        paint.strokeWidth = 3f
        paint.color = Color.rgb(58, 58, 58)
        val cross = Path().apply {
            moveTo(cx - 168f, cy - 86f); lineTo(cx + 168f, cy + 86f)
            moveTo(cx + 168f, cy - 86f); lineTo(cx - 168f, cy + 86f)
        }
        canvas.drawPath(cross, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()

        if (frame >= 170) {
            val chars = "Infinite\nComparison"
            val visible = (((frame - 170) / 2.4f).toInt()).coerceIn(0, chars.length)
            val text = chars.take(visible)
            textPaint.alpha = (255 * alpha).roundToInt().coerceIn(0, 255)
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textPaint.textSize = 34f
            val lines = text.split('\n')
            lines.forEachIndexed { index, line -> canvas.drawText(line, cx, 640f + index * 38f, textPaint) }
            textPaint.alpha = 255
        }
    }

    private fun drawContent(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        val positions = linkedMapOf<Int, Float>()
        if (frame < spec.continuousStartFrame) {
            val starts = spec.openingStarts
            for (index in 0 until min(4, project.cards.size)) {
                val start = starts.getOrElse(index) { starts.lastOrNull().orZero() + index * 140 }
                if (frame >= start) positions[index] = index * spec.slotPitch
            }
        } else {
            val scroll = exactScroll(spec, frame) ?: ((frame - spec.continuousStartFrame) * 2f)
            project.cards.indices.forEach { index ->
                val x = index * spec.slotPitch - scroll
                if (x > -spec.slotPitch && x < 1920f + spec.slotPitch) positions[index] = x
            }
        }

        positions.forEach { (index, x) -> drawCardBody(canvas, project, project.cards[index], x, spec, frame, index) }
        if (project.creditsEnabled && frame in spec.openingStarts.firstOrNull().orZero() until spec.continuousStartFrame) drawDisclaimer(canvas, frame, spec)
        positions.forEach { (index, x) -> drawBadge(canvas, project, index, x, frame, spec) }
        positions.forEach { (index, x) ->
            if (project.cards[index].imageLayer.equals("front", true)) drawFrontArtwork(canvas, project.cards[index], x, spec)
        }
    }

    private fun exactScroll(spec: RendererSpec, frame: Int): Float? {
        if (frame < spec.continuousStartFrame) return null
        val segment = (frame - spec.continuousStartFrame) / 4096
        return spec.track("relationships.scroll.$segment", frame)
    }

    private fun drawCardBody(canvas: Canvas, project: StudioProject, card: StudioCard, slotX: Float, spec: RendererSpec, frame: Int, index: Int) {
        val left = slotX + spec.bodyInset
        val right = left + spec.bodyWidth
        val descriptionHeight = if (card.description.isBlank()) 0f else 115f
        val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
        val imageBottom = 1080f - descriptionHeight - titleHeight
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(30, 30, 30)
        canvas.drawRect(left, 0f, right, imageBottom, paint)

        val entry = RelationshipsTimeline.cardEntryFrame(projectSize = Int.MAX_VALUE, index = index, spec = spec)
        val local = frame - entry
        val reveal = if (index < 4) ((local - 52f) / 42f).coerceIn(0f, 1f) else 1f
        if (!card.imageLayer.equals("front", true) && reveal > 0f) {
            val revealBottom = imageBottom * smooth(reveal)
            canvas.save(); canvas.clipRect(left, 0f, right, revealBottom)
            drawArtwork(canvas, card, RectF(left, 0f, right, imageBottom)); canvas.restore()
        }

        var cursor = imageBottom
        if (card.title.isNotBlank()) {
            paint.color = spec.titleBackgroundColor
            canvas.drawRect(left, cursor, right, cursor + titleHeight, paint)
            drawFitted(canvas, card.title, RectF(left + 10f, cursor + 1f, right - 10f, cursor + titleHeight - 1f), spec.titleTextColor, spec.titleTextSize, true, 1, project)
            cursor += titleHeight
        }
        if (card.description.isNotBlank()) {
            paint.color = spec.descriptionBackgroundColor
            canvas.drawRect(left, cursor, right, 1080f, paint)
            drawFitted(canvas, card.description, RectF(left + 11f, cursor + 4f, right - 11f, 1076f), spec.descriptionTextColor, spec.descriptionTextSize, false, 4, project)
        }
    }

    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec) {
        val desc = if (card.description.isBlank()) 0f else 115f
        val title = if (card.title.isBlank()) 0f else spec.titleHeight
        drawArtwork(canvas, card, RectF(slotX + spec.bodyInset, 0f, slotX + spec.bodyInset + spec.bodyWidth, 1080f - desc - title))
    }

    private fun drawArtwork(canvas: Canvas, card: StudioCard, destination: RectF) {
        val bitmap = loadImage(card.image) ?: return
        val left = (bitmap.width * card.imageCropLeft.coerceIn(0.0, 0.95)).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * card.imageCropTop.coerceIn(0.0, 0.95)).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * (1.0 - card.imageCropRight.coerceIn(0.0, 0.95))).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * (1.0 - card.imageCropBottom.coerceIn(0.0, 0.95))).roundToInt().coerceIn(top + 1, bitmap.height)
        val source = Rect(left, top, right, bottom)
        val base = max(destination.width() / source.width().coerceAtLeast(1), destination.height() / source.height().coerceAtLeast(1))
        val scale = base * card.imageScale.coerceIn(0.05, 12.0).toFloat()
        val w = source.width() * scale
        val h = source.height() * scale
        val cx = destination.centerX() + card.imageX.toFloat()
        val cy = destination.centerY() + card.imageY.toFloat()
        val target = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.save(); canvas.clipRect(destination)
        if (card.imageRotation != 0.0) canvas.rotate(card.imageRotation.toFloat(), cx, cy)
        paint.alpha = 255; canvas.drawBitmap(bitmap, source, target, paint); canvas.restore()
    }

    private fun loadImage(path: String): Bitmap? {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://")) return null
        imageCache[path]?.let { if (!it.isRecycled) return it }
        val file = File(path); if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()?.also { imageCache[path] = it }
    }

    private fun drawBadge(canvas: Canvas, project: StudioProject, index: Int, cardX: Float, frame: Int, spec: RendererSpec) {
        if (!project.showBadges) return
        val card = project.cards[index]
        if (card.value.isBlank() && card.badgeHeader.isBlank()) return
        val entry = RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec)
        val local = frame - entry
        if (local < 0) return
        val scale = (spec.track("relationships.badge.scale", local) ?: if (local < 45) smooth(local / 45f) else 1f).coerceIn(0f, 1.25f)
        val yOffset = spec.track("relationships.badge.y", local) ?: 0f
        canvas.save(); canvas.translate(cardX, yOffset); canvas.scale(scale * spec.badgeScale, scale * spec.badgeScale, spec.badgeCenterX, spec.badgeCenterY)
        val path = octagon(spec.badgeCenterX, spec.badgeCenterY, 184f, 177f)
        paint.style = Paint.Style.FILL; paint.color = spec.badgeColor; canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = spec.badgeDarkColor; canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        drawBadgeText(canvas, project, card, spec)
        canvas.restore()
    }

    private fun octagon(cx: Float, cy: Float, rx: Float, ry: Float): Path {
        val pts = arrayOf(
            cx - 92f to cy - ry,
            cx + 92f to cy - ry,
            cx + rx to cy - 88f,
            cx + rx to cy + 86f,
            cx + 92f to cy + ry,
            cx - 92f to cy + ry,
            cx - rx to cy + 86f,
            cx - rx to cy - 88f,
        )
        return Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.first, p.second) else lineTo(p.first, p.second) }; close() }
    }

    private fun drawBadgeText(canvas: Canvas, project: StudioProject, card: StudioCard, spec: RendererSpec) {
        val raw = card.value.trim()
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val primary = parts.firstOrNull().orEmpty()
        val unit = parts.getOrNull(1).orEmpty().ifBlank { "People" }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = spec.badgeTextColor
        textPaint.typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", Typeface.NORMAL), Typeface.NORMAL)
        drawBadgeLine(
            canvas = canvas,
            text = card.badgeHeader.ifBlank { "1 in" },
            x = spec.badgeCenterX,
            y = spec.badgeCenterY - 75f,
            preferredSize = 31f,
            minimumSize = 17f,
            maxWidth = 230f,
        )
        drawBadgeLine(
            canvas = canvas,
            text = primary,
            x = spec.badgeCenterX,
            y = spec.badgeCenterY + 12f,
            preferredSize = 72f,
            minimumSize = 28f,
            maxWidth = 300f,
        )
        drawBadgeLine(
            canvas = canvas,
            text = unit,
            x = spec.badgeCenterX,
            y = spec.badgeCenterY + 70f,
            preferredSize = 29f,
            minimumSize = 16f,
            maxWidth = 245f,
        )
    }

    private fun drawBadgeLine(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        preferredSize: Float,
        minimumSize: Float,
        maxWidth: Float,
    ) {
        if (text.isBlank()) return
        textPaint.textSize = preferredSize
        val measured = textPaint.measureText(text).coerceAtLeast(1f)
        textPaint.textSize = if (measured <= maxWidth) {
            preferredSize
        } else {
            (preferredSize * maxWidth / measured).coerceAtLeast(minimumSize)
        }
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawDisclaimer(canvas: Canvas, frame: Int, spec: RendererSpec) {
        val first = spec.openingStarts.firstOrNull().orZero()
        val p = ((frame - first) / 70f).coerceIn(0f, 1f)
        val x = 1450f + 470f * (1f - smooth(p))
        if (x >= 1920f) return
        paint.color = Color.rgb(22, 22, 22); canvas.drawRect(x, 0f, 1920f, 1080f, paint)
        textPaint.textAlign = Paint.Align.LEFT; textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL); textPaint.textSize = 26f
        val lines = listOf(
            "DISCLAIMER: This", "comparison video", "is based on public", "data, surveys,", "public comments", "& discussions and", "approximate", "estimations that", "might be", "subjected to some", "degree of error.",
        )
        var y = 220f
        lines.forEachIndexed { i, line -> textPaint.color = if (i == 0) Color.rgb(178, 0, 22) else Color.LTGRAY; canvas.drawText(line, x + 30f, y, textPaint); y += 38f }
    }

    private fun drawOutro(canvas: Canvas, project: StudioProject, frame: Int, contentEnd: Int, spec: RendererSpec) {
        val local = frame - contentEnd
        canvas.drawColor(spec.backgroundColor)
        val last = project.cards.last()
        val cardX = spec.track("relationships.outro.card.x", frame) ?: when {
            local < 80 -> lerp(320f, 781f, smooth(local / 80f))
            else -> 781f
        }
        drawCardBody(canvas, project, last, cardX, spec, frame, project.cards.lastIndex)
        drawBadge(canvas, project, project.cards.lastIndex, cardX, frame, spec)

        if (local >= 58) {
            paint.color = Color.rgb(28, 28, 28); canvas.drawRect(1290f, 180f, 1732f, 910f, paint)
            textPaint.textAlign = Paint.Align.CENTER; textPaint.color = Color.rgb(145, 145, 145); textPaint.textSize = 31f; textPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            canvas.drawText("WATCH MORE", 1511f, 235f, textPaint)
        }
        if (local >= 70) {
            val full = "Which relationship type\nare you in right now?"
            val chars = (((local - 70) * 0.52f).toInt()).coerceIn(0, full.length)
            drawTyped(canvas, full.take(chars), 40f, 390f, 37f, Color.WHITE)
        }
        if (local >= 225) {
            val full = "Comment below!"
            val chars = (((local - 225) * 0.6f).toInt()).coerceIn(0, full.length)
            drawTyped(canvas, full.take(chars), 40f, 500f, 37f, Color.rgb(244, 159, 0))
        }
        if (local >= 290) {
            textPaint.textAlign = Paint.Align.LEFT; textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD); textPaint.textSize = 34f; textPaint.color = Color.rgb(224, 10, 34)
            canvas.drawText("SUBSCRIBE", 40f, 900f, textPaint)
            textPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textPaint.color = Color.LTGRAY; textPaint.textSize = 30f
            canvas.drawText("for more", 220f, 900f, textPaint); canvas.drawText("comparison videos.", 40f, 944f, textPaint)
        }
        val fadeStart = max(0, RelationshipsTimeline.totalFrameCount(project, spec) - contentEnd - 42)
        if (local >= fadeStart) {
            val a = (((local - fadeStart) / 42f) * 255).roundToInt().coerceIn(0, 255)
            paint.color = Color.argb(a, 0, 0, 0); canvas.drawRect(0f, 0f, 1920f, 1080f, paint)
        }
    }

    private fun drawTyped(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        textPaint.textAlign = Paint.Align.LEFT; textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL); textPaint.textSize = size; textPaint.color = color
        text.split('\n').forEachIndexed { i, line -> canvas.drawText(line, x, y + i * (size + 7f), textPaint) }
    }

    private fun drawFitted(canvas: Canvas, text: String, box: RectF, color: Int, preferred: Float, bold: Boolean, maxLines: Int, project: StudioProject) {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        textPaint.color = color; textPaint.typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", style), style); textPaint.textAlign = Paint.Align.CENTER
        var size = preferred; var lines = wrap(text, box.width(), size, maxLines)
        while ((lines.size > maxLines || lines.any { measure(it, size) > box.width() }) && size > 11f) { size -= 1f; lines = wrap(text, box.width(), size, maxLines) }
        textPaint.textSize = size
        val fm = textPaint.fontMetrics; val lineHeight = (fm.descent - fm.ascent) * 0.92f
        var y = box.centerY() - (lines.size - 1) * lineHeight / 2f - (fm.ascent + fm.descent) / 2f
        lines.take(maxLines).forEach { canvas.drawText(it, box.centerX(), y, textPaint); y += lineHeight }
    }

    private fun wrap(text: String, width: Float, size: Float, maxLines: Int): List<String> {
        textPaint.textSize = size; val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }; if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>(); var current = ""
        words.forEach { word ->
            val trial = if (current.isBlank()) word else "$current $word"
            if (textPaint.measureText(trial) <= width || current.isBlank()) current = trial else { lines += current; current = word }
        }
        if (current.isNotBlank()) lines += current
        if (lines.size <= maxLines) return lines
        return lines.take(maxLines - 1) + lines.drop(maxLines - 1).joinToString(" ")
    }

    private fun measure(text: String, size: Float): Float { textPaint.textSize = size; return textPaint.measureText(text) }
    private fun smooth(x: Float): Float { val p = x.coerceIn(0f, 1f); return p * p * (3f - 2f * p) }
    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p.coerceIn(0f, 1f)
    private fun Int?.orZero() = this ?: 0
}

object RelationshipsTimeline {
    fun isRelationships(spec: RendererSpec): Boolean = spec.engine == "relationships-exact" || spec.id.startsWith("relationships.")

    fun contentEndFrame(project: StudioProject, spec: RendererSpec): Int {
        spec.track("relationships.content_end", 0)?.roundToInt()?.let { canonical ->
            if (spec.canonicalCardCount <= 0 || project.cards.size == spec.canonicalCardCount) return canonical
        }
        if (project.cards.size <= 4) return spec.openingStarts.getOrElse(project.cards.lastIndex.coerceAtLeast(0)) { 384 } + 180
        val last = project.cards.lastIndex
        return cardEntryFrame(project.cards.size, last, spec) + 340
    }

    fun totalFrameCount(project: StudioProject, spec: RendererSpec): Int {
        if (spec.canonicalFrameCount > 0 && spec.canonicalCardCount > 0 && project.cards.size == spec.canonicalCardCount) return spec.canonicalFrameCount
        return contentEndFrame(project, spec) + max(300, spec.outroFrames)
    }

    fun cardEntryFrame(projectSize: Int, index: Int, spec: RendererSpec): Int {
        if (index < 4) return spec.openingStarts.getOrElse(index) { 384 + index * 140 }
        val target = 1920f
        val base = index * spec.slotPitch
        var low = spec.continuousStartFrame
        var high = spec.track("relationships.content_end", 0)?.roundToInt() ?: (low + projectSize * 300)
        repeat(16) {
            val mid = (low + high) / 2
            val segment = (mid - spec.continuousStartFrame) / 4096
            val scroll = spec.track("relationships.scroll.$segment", mid) ?: ((mid - spec.continuousStartFrame) * 2f)
            if (base - scroll <= target) high = mid else low = mid + 1
        }
        return high
    }
}
