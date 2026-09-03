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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class NativeFrameRenderer {
    private val imageCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > 8
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    @Synchronized
    fun render(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): Bitmap {
        val reference = Bitmap.createBitmap(REFERENCE_WIDTH, REFERENCE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(reference)
        drawReference(canvas, project, frameIndex.coerceAtLeast(0), RendererRuntime.active)
        if (outputWidth == REFERENCE_WIDTH && outputHeight == REFERENCE_HEIGHT) return reference

        val output = Bitmap.createBitmap(outputWidth.coerceAtLeast(2), outputHeight.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            reference,
            Rect(0, 0, reference.width, reference.height),
            Rect(0, 0, output.width, output.height),
            paint,
        )
        reference.recycle()
        return output
    }

    @Synchronized
    fun renderRgba(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): ByteArray {
        val bitmap = render(project, frameIndex, outputWidth, outputHeight)
        try {
            val bytes = ByteArray(bitmap.byteCount)
            val buffer = ByteBuffer.wrap(bytes)
            bitmap.copyPixelsToBuffer(buffer)
            return bytes
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawReference(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        canvas.drawColor(spec.backgroundColor)
        if (project.cards.isEmpty()) return

        val contentEnd = NativeTimeline.contentEndFrame(project, spec)
        val trackTime = rendererTrackTime(project, spec, frame)

        if (frame < contentEnd) {
            drawCards(canvas, project, frame, trackTime, spec)
            return
        }

        // Keep the last comparison frame underneath the measured-length outro.
        drawCards(canvas, project, (contentEnd - 1).coerceAtLeast(0), trackTime, spec)
        val outro = frame - contentEnd
        val wipeEnd = spec.endWipeFrames
        val riseEnd = wipeEnd + spec.endRiseFrames
        val holdEnd = riseEnd + spec.endHoldFrames
        val fadeEnd = holdEnd + spec.fadeFrames

        when {
            outro < wipeEnd -> {
                val p = outro.toFloat() / spec.endWipeFrames.coerceAtLeast(1)
                paint.color = Color.BLACK
                canvas.drawRect(0f, REFERENCE_HEIGHT * (1f - p), REFERENCE_WIDTH.toFloat(), REFERENCE_HEIGHT.toFloat(), paint)
            }
            outro < riseEnd -> {
                paint.color = Color.BLACK
                canvas.drawRect(0f, 0f, REFERENCE_WIDTH.toFloat(), REFERENCE_HEIGHT.toFloat(), paint)
                paint.color = Color.WHITE
                val p = (outro - wipeEnd).toFloat() / spec.endRiseFrames.coerceAtLeast(1)
                canvas.drawRect(0f, REFERENCE_HEIGHT * (1f - p), REFERENCE_WIDTH.toFloat(), REFERENCE_HEIGHT.toFloat(), paint)
            }
            outro < holdEnd -> canvas.drawColor(Color.WHITE)
            outro < fadeEnd -> {
                canvas.drawColor(Color.WHITE)
                val p = (outro - holdEnd).toFloat() / spec.fadeFrames.coerceAtLeast(1)
                paint.color = Color.argb((255f * p).toInt().coerceIn(0, 255), 0, 0, 0)
                canvas.drawRect(0f, 0f, REFERENCE_WIDTH.toFloat(), REFERENCE_HEIGHT.toFloat(), paint)
            }
            else -> canvas.drawColor(Color.BLACK)
        }
    }

    private fun drawCards(canvas: Canvas, project: StudioProject, frame: Int, timeMs: Int, spec: RendererSpec) {
        val continuousStep = NativeTimeline.continuousStepFrames(project, spec)
        val calculatedScroll = if (frame >= spec.continuousStartFrame) {
            (frame - spec.continuousStartFrame).toFloat() / continuousStep.coerceAtLeast(1) * spec.slotPitch
        } else {
            0f
        }
        val scroll = spec.track("scroll", timeMs) ?: calculatedScroll

        project.cards.forEachIndexed { index, card ->
            val baseX = when {
                frame >= spec.continuousStartFrame -> index * spec.slotPitch - scroll
                index < 4 -> {
                    val start = NativeTimeline.cardStartFrame(project, spec, index)
                    if (frame < start) return@forEachIndexed
                    val p = bodyProgress((frame - start).toFloat() / spec.bodySlideFrames.coerceAtLeast(1))
                    lerp(REFERENCE_WIDTH.toFloat(), index * spec.slotPitch, p)
                }
                else -> return@forEachIndexed
            }
            var x = baseX + (spec.track("card.$index.x", timeMs) ?: 0f)
            var y = spec.track("card.$index.y", timeMs) ?: 0f
            val alpha = (spec.track("card.$index.alpha", timeMs) ?: 1f).coerceIn(0f, 1f)
            if (x > REFERENCE_WIDTH + spec.slotPitch || x + spec.slotPitch < -spec.slotPitch) return@forEachIndexed

            canvas.save()
            canvas.translate(x, y)
            if (alpha < 0.999f) canvas.saveLayerAlpha(null, (alpha * 255).toInt())
            drawCard(canvas, project, card, index, frame, timeMs, spec)
            if (alpha < 0.999f) canvas.restore()
            canvas.restore()
        }
    }

    private fun drawCard(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        index: Int,
        frame: Int,
        timeMs: Int,
        spec: RendererSpec,
    ) {
        val bodyLeft = spec.bodyInset
        val bodyRight = bodyLeft + spec.bodyWidth
        val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
        val imageBottom = RendererArtworkLayout.imageBottom(card, spec)

        paint.color = Color.rgb(19, 141, 219)
        canvas.drawRect(bodyLeft, 0f, bodyRight, imageBottom, paint)
        drawArtwork(canvas, card, RectF(bodyLeft, 0f, bodyRight, imageBottom))

        var cursor = imageBottom
        if (card.title.isNotBlank()) {
            paint.color = spec.titleBackgroundColor
            canvas.drawRect(bodyLeft, cursor, bodyRight, cursor + titleHeight, paint)
            drawWrappedText(
                canvas = canvas,
                text = card.title,
                box = RectF(bodyLeft + 20f, cursor + 5f, bodyRight - 20f, cursor + titleHeight - 5f),
                color = spec.titleTextColor,
                preferredSize = spec.titleTextSize,
                minSize = 18f,
                maxLines = 2,
                typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", Typeface.BOLD), Typeface.BOLD),
            )
            cursor += titleHeight
        }
        if (card.description.isNotBlank()) {
            paint.color = spec.descriptionBackgroundColor
            canvas.drawRect(bodyLeft, cursor, bodyRight, REFERENCE_HEIGHT.toFloat(), paint)
            drawWrappedText(
                canvas = canvas,
                text = card.description,
                box = RectF(bodyLeft + 18f, cursor + 8f, bodyRight - 18f, REFERENCE_HEIGHT - 8f),
                color = spec.descriptionTextColor,
                preferredSize = spec.descriptionTextSize,
                minSize = 14f,
                maxLines = 3,
                typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", Typeface.BOLD), Typeface.BOLD),
            )
        }

        if (project.showBadges && (card.value.isNotBlank() || card.badgeHeader.isNotBlank())) {
            val start = NativeTimeline.cardStartFrame(project, spec, index)
            val localFrame = frame - start
            val badgeY = when {
                index < 4 -> 0f
                else -> sampleLaterBadgeY(localFrame, spec)
            } + (spec.track("badge.$index.y", timeMs) ?: 0f)
            val badgeX = spec.track("badge.$index.x", timeMs) ?: 0f
            val badgeAlpha = (spec.track("badge.$index.alpha", timeMs) ?: 1f).coerceIn(0f, 1f)
            canvas.save()
            canvas.translate(badgeX, badgeY)
            if (badgeAlpha < 0.999f) canvas.saveLayerAlpha(null, (badgeAlpha * 255).toInt())
            drawBadge(canvas, project, card, localFrame, spec)
            if (badgeAlpha < 0.999f) canvas.restore()
            canvas.restore()
        }
    }

    private fun drawArtwork(canvas: Canvas, card: StudioCard, destination: RectF) {
        val bitmap = loadImage(card.image) ?: return
        val leftCrop = card.imageCropLeft.coerceIn(0.0, 0.95)
        val topCrop = card.imageCropTop.coerceIn(0.0, 0.95)
        val rightCrop = card.imageCropRight.coerceIn(0.0, 0.95)
        val bottomCrop = card.imageCropBottom.coerceIn(0.0, 0.95)
        val srcLeft = (bitmap.width * leftCrop).toInt().coerceIn(0, bitmap.width - 1)
        val srcTop = (bitmap.height * topCrop).toInt().coerceIn(0, bitmap.height - 1)
        val srcRight = (bitmap.width * (1.0 - rightCrop)).toInt().coerceIn(srcLeft + 1, bitmap.width)
        val srcBottom = (bitmap.height * (1.0 - bottomCrop)).toInt().coerceIn(srcTop + 1, bitmap.height)
        val source = Rect(srcLeft, srcTop, srcRight, srcBottom)
        val sourceWidth = source.width().toFloat().coerceAtLeast(1f)
        val sourceHeight = source.height().toFloat().coerceAtLeast(1f)
        val baseScale = max(destination.width() / sourceWidth, destination.height() / sourceHeight)
        val scale = baseScale * card.imageScale.coerceIn(0.05, 12.0).toFloat()
        val drawnWidth = sourceWidth * scale
        val drawnHeight = sourceHeight * scale
        val cx = destination.centerX() + card.imageX.toFloat()
        val cy = destination.centerY() + card.imageY.toFloat()
        val target = RectF(cx - drawnWidth / 2f, cy - drawnHeight / 2f, cx + drawnWidth / 2f, cy + drawnHeight / 2f)

        canvas.save()
        canvas.clipRect(destination)
        if (card.imageRotation != 0.0) canvas.rotate(card.imageRotation.toFloat(), cx, cy)
        paint.alpha = 255
        canvas.drawBitmap(bitmap, source, target, paint)
        canvas.restore()
    }

    private fun loadImage(path: String): Bitmap? {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://")) return null
        imageCache[path]?.let { if (!it.isRecycled) return it }
        val file = File(path)
        if (!file.isFile) return null
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        imageCache[path] = decoded
        return decoded
    }

    private fun drawBadge(canvas: Canvas, project: StudioProject, card: StudioCard, localFrame: Int, spec: RendererSpec) {
        val path = badgePath(spec)
        paint.style = Paint.Style.FILL
        paint.color = spec.badgeColor
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f * spec.badgeScale
        paint.color = spec.badgeDarkColor
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL

        val shineProgress = ((localFrame - spec.shineStartFrame).toFloat() / spec.shineFrames.coerceAtLeast(1)).coerceIn(0f, 1f)
        if (localFrame in spec.shineStartFrame..(spec.shineStartFrame + spec.shineFrames)) {
            canvas.save()
            canvas.clipPath(path)
            val center = spec.badgeCenterX
            val travel = 540f * spec.badgeScale
            val stripeX = center - travel / 2f + travel * shineProgress
            canvas.rotate(-28f, spec.badgeCenterX, spec.badgeCenterY)
            paint.color = spec.shineColor
            canvas.drawRect(stripeX - 25f * spec.badgeScale, -60f, stripeX + 25f * spec.badgeScale, 470f, paint)
            canvas.restore()
        }

        val header = card.badgeHeader.trim()
        val parts = card.value.trim().split(Regex("\\s+"), limit = 2)
        val primary = parts.firstOrNull().orEmpty()
        val unit = parts.getOrNull(1).orEmpty()

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = spec.badgeTextColor
        textPaint.typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", Typeface.BOLD), Typeface.BOLD)
        val cx = spec.badgeCenterX
        val cy = spec.badgeCenterY

        when {
            header.isNotBlank() && unit.isNotBlank() -> {
                drawBadgeText(canvas, header, cx, cy - 54f * spec.badgeScale, spec.badgeHeaderSize * spec.badgeScale)
                drawBadgeText(canvas, primary, cx, cy + 2f * spec.badgeScale, spec.badgeValueSize * spec.badgeScale)
                drawBadgeText(canvas, unit, cx, cy + 48f * spec.badgeScale, spec.badgeUnitSize * spec.badgeScale)
            }
            header.isNotBlank() -> {
                drawBadgeText(canvas, header, cx, cy - 30f * spec.badgeScale, spec.badgeHeaderSize * spec.badgeScale)
                drawBadgeText(canvas, card.value.trim(), cx, cy + 30f * spec.badgeScale, spec.badgeValueSize * 0.88f * spec.badgeScale)
            }
            unit.isNotBlank() -> {
                drawBadgeText(canvas, primary, cx, cy - 10f * spec.badgeScale, spec.badgeValueSize * spec.badgeScale)
                drawBadgeText(canvas, unit, cx, cy + 40f * spec.badgeScale, spec.badgeUnitSize * spec.badgeScale)
            }
            else -> drawBadgeText(canvas, primary, cx, cy + 12f * spec.badgeScale, spec.badgeValueSize * spec.badgeScale)
        }
    }

    private fun badgePath(spec: RendererSpec): Path {
        val source = arrayOf(
            224f to 16f,
            396f to 104f,
            396f to 292f,
            252f to 380f,
            72f to 292f,
            72f to 104f,
        )
        val path = Path()
        source.forEachIndexed { index, point ->
            val x = spec.badgeCenterX + (point.first - 240f) * spec.badgeScale
            val y = spec.badgeCenterY + (point.second - 198f) * spec.badgeScale
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun drawBadgeText(canvas: Canvas, text: String, x: Float, baseline: Float, size: Float) {
        var useSize = size
        textPaint.textSize = useSize
        while (textPaint.measureText(text) > 245f && useSize > 14f) {
            useSize -= 1f
            textPaint.textSize = useSize
        }
        canvas.drawText(text, x, baseline - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        box: RectF,
        color: Int,
        preferredSize: Float,
        minSize: Float,
        maxLines: Int,
        typeface: Typeface,
    ) {
        var size = preferredSize
        var lines: List<String>
        while (true) {
            textPaint.textSize = size
            textPaint.typeface = typeface
            lines = wrap(text, box.width(), textPaint, maxLines)
            val lineHeight = size * 1.05f
            if ((lines.size * lineHeight <= box.height() && lines.all { textPaint.measureText(it) <= box.width() }) || size <= minSize) break
            size -= 1f
        }
        textPaint.color = color
        textPaint.textAlign = Paint.Align.CENTER
        val lineHeight = size * 1.05f
        val total = lineHeight * lines.size
        var y = box.centerY() - total / 2f - textPaint.ascent()
        lines.forEach { line ->
            canvas.drawText(line, box.centerX(), y, textPaint)
            y += lineHeight
        }
    }

    private fun wrap(text: String, width: Float, paint: Paint, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= width || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
                if (lines.size == maxLines - 1) break
            }
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines += current
        return lines.take(maxLines)
    }

    private fun rendererTrackTime(project: StudioProject, spec: RendererSpec, frame: Int): Int = when (spec.timelineUnit) {
        "milliseconds" -> (frame.toLong() * 1000L / project.fps.coerceAtLeast(1)).toInt()
        "normalized" -> {
            val frames = (spec.canonicalFrameCount.takeIf { it > 1 }
                ?: NativeTimeline.totalFrameCount(project, spec).coerceAtLeast(2))
            (frame.toLong() * 1000L / (frames - 1).coerceAtLeast(1)).toInt().coerceIn(0, 1000)
        }
        else -> frame
    }

    private fun sampleLaterBadgeY(localFrame: Int, spec: RendererSpec): Float {
        if (localFrame < spec.laterBadgeFallStartFrame) return -430f * spec.badgeScale
        if (localFrame >= spec.laterBadgeFallEndFrame) return 0f
        val points = LATER_BADGE_Y
        for (index in 1 until points.size) {
            val right = points[index]
            if (localFrame <= right.first) {
                val left = points[index - 1]
                val p = (localFrame - left.first).toFloat() / (right.first - left.first).coerceAtLeast(1)
                return lerp(left.second, right.second, p) * spec.badgeScale
            }
        }
        return 0f
    }

    private fun bodyProgress(normalized: Float): Float {
        val frame = normalized.coerceIn(0f, 1f) * 80f
        for (index in 1 until BODY_PROGRESS.size) {
            val right = BODY_PROGRESS[index]
            if (frame <= right.first) {
                val left = BODY_PROGRESS[index - 1]
                val p = ((frame - left.first) / (right.first - left.first).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                val smooth = p * p * (3f - 2f * p)
                return lerp(left.second, right.second, smooth)
            }
        }
        return 1f
    }

    private fun lerp(a: Float, b: Float, p: Float): Float = a + (b - a) * p

    companion object {
        const val REFERENCE_WIDTH = 1920
        const val REFERENCE_HEIGHT = 1080

        private val BODY_PROGRESS = arrayOf(
            0f to 0f, 2f to 0f, 5f to 0.019f, 10f to 0.101f, 15f to 0.300f,
            20f to 0.515f, 25f to 0.653f, 30f to 0.746f, 35f to 0.813f,
            40f to 0.864f, 45f to 0.901f, 50f to 0.931f, 55f to 0.954f,
            60f to 0.971f, 65f to 0.983f, 70f to 0.994f, 75f to 0.998f, 80f to 1f,
        )

        private val LATER_BADGE_Y = arrayOf(
            122 to -430f, 142 to -410f, 151 to -386f, 152 to -381f, 154 to -381f,
            156 to -341f, 158 to -321f, 160 to -300f, 162 to -279f, 164 to -266f,
            166 to -246f, 168 to -226f, 170 to -206f, 172 to -187f, 174 to -175f,
            176 to -156f, 178 to -138f, 180 to -121f, 182 to -105f, 184 to -94f,
            186 to -80f, 188 to -66f, 190 to -53f, 192 to -41f, 194 to -34f,
            196 to -25f, 198 to -17f, 200 to -10f, 202 to -5f, 204 to -2f, 206 to 0f,
        )
    }
}

object NativeTimeline {
    fun cardStartFrame(project: StudioProject, spec: RendererSpec, index: Int): Int {
        if (index < 4) return spec.openingStarts.getOrElse(index) { index * 120 }
        if (project.autoLength) return spec.continuousStartFrame + (index - 4) * spec.continuousStepFrames
        return spec.continuousStartFrame + ((index - 4) * continuousStepFrames(project, spec))
    }

    fun continuousStepFrames(project: StudioProject, spec: RendererSpec): Int {
        if (project.autoLength || project.cards.size <= 4) return spec.continuousStepFrames
        val fps = project.fps.coerceAtLeast(1)
        val requestedTotal = (project.customLengthSeconds * fps).toInt().coerceAtLeast(1)
        val requestedContent = (requestedTotal - spec.outroFrames).coerceAtLeast(spec.continuousStartFrame + 1)
        val intervals = (project.cards.size - 4).coerceAtLeast(1)
        return ((requestedContent - spec.continuousStartFrame) / intervals).coerceAtLeast(1)
    }

    fun contentEndFrame(project: StudioProject, spec: RendererSpec): Int {
        val count = project.cards.size
        if (count <= 0) return 0
        if (!project.autoLength) {
            val requested = (project.customLengthSeconds * project.fps.coerceAtLeast(1)).toInt() - spec.outroFrames
            val minimum = if (count <= 4) spec.openingEnds.getOrElse(count - 1) { spec.continuousStartFrame } else spec.continuousStartFrame + (count - 4)
            return max(minimum, requested)
        }
        if (count <= 4) return spec.openingEnds.getOrElse(count - 1) { spec.continuousStartFrame }
        return spec.continuousStartFrame + (count - 4) * spec.continuousStepFrames
    }

    fun totalFrameCount(project: StudioProject, spec: RendererSpec): Int = contentEndFrame(project, spec) + spec.outroFrames
}
