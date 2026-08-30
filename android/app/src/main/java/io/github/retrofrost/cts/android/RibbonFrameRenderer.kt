package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native interpreter for declarative Ribbon .renderer bundles.
 *
 * The bundle contains the measured frame clocks in ordinary RendererTrack
 * entries. No DEX, native library, script, or other executable payload is
 * loaded from a .renderer file.
 */
class RibbonFrameRenderer {
    private val imageCache = object : LinkedHashMap<String, Bitmap>(10, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > 10
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val boldTypeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val badgeShadowBlur = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    private val exactShineBroadBlur = BlurMaskFilter(8.5f, BlurMaskFilter.Blur.NORMAL)
    private val exactShineCoreBlur = BlurMaskFilter(2.4f, BlurMaskFilter.Blur.NORMAL)
    private val legacyShineBroadBlur = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    private val legacyShineCoreBlur = BlurMaskFilter(2.8f, BlurMaskFilter.Blur.NORMAL)
    private val shineBroadPath = Path()
    private val shineCorePath = Path()
    private val badgePath = Path().apply {
        moveTo(224f, 16f)
        lineTo(396f, 104f)
        lineTo(396f, 292f)
        lineTo(252f, 380f)
        lineTo(72f, 292f)
        lineTo(72f, 104f)
        close()
    }

    @Synchronized
    fun render(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): Bitmap {
        val width = outputWidth.coerceAtLeast(2)
        val height = outputHeight.coerceAtLeast(2)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        if (width == REFERENCE_WIDTH && height == REFERENCE_HEIGHT) {
            drawReference(canvas, project, frameIndex.coerceAtLeast(0), RendererRuntime.active)
        } else {
            // Render directly into the requested target instead of allocating a full
            // 1920x1080 intermediate and a second scaled bitmap for every frame.
            canvas.save()
            canvas.scale(width / REFERENCE_WIDTH.toFloat(), height / REFERENCE_HEIGHT.toFloat())
            drawReference(canvas, project, frameIndex.coerceAtLeast(0), RendererRuntime.active)
            canvas.restore()
        }
        return output
    }

    @Synchronized
    fun renderRgba(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): ByteArray {
        val bitmap = render(project, frameIndex, outputWidth, outputHeight)
        try {
            val bytes = ByteArray(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
            return bytes
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawReference(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        canvas.drawColor(frameBackgroundColor(spec, frame))
        if (project.cards.isEmpty()) return
        val contentEnd = RibbonTimeline.contentEndFrame(project, spec)
        if (frame < contentEnd) {
            drawContent(canvas, project, frame, spec)
        } else {
            drawOutro(canvas, project, frame, contentEnd, spec)
        }
    }

    private fun drawContent(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        val positions = positionsForFrame(project, frame, spec)
        if (positions.isEmpty()) return

        val sortedIndices = positions.keys.sorted()
        var bodyOrder = sortedIndices
        if (frame < spec.continuousStartFrame) {
            val active = bodyOrder.maxOrNull()
            if (active != null) bodyOrder = listOf(active) + bodyOrder.filter { it != active }
        }

        bodyOrder.forEach { index ->
            val x = positions.getValue(index)
            val clip = openingClip(project, spec, index, frame)
            if (clip != null) {
                if (clip.second > clip.first) {
                    canvas.save()
                    canvas.clipRect(clip.first, 0f, clip.second, REFERENCE_HEIGHT.toFloat())
                    drawCardBody(canvas, project, project.cards[index], x, spec)
                    canvas.restore()
                }
            } else {
                drawCardBody(canvas, project, project.cards[index], x, spec)
            }
        }

        drawOpeningCredits(canvas, project, frame, spec)

        sortedIndices.forEach { index ->
            drawBadge(canvas, project, index, positions.getValue(index), frame, spec)
        }
        sortedIndices.forEach { index ->
            if (project.cards[index].imageLayer.equals("front", ignoreCase = true)) {
                val x = positions.getValue(index)
                val clip = openingClip(project, spec, index, frame)
                if (clip != null) {
                    if (clip.second > clip.first) {
                        canvas.save()
                        canvas.clipRect(clip.first, 0f, clip.second, REFERENCE_HEIGHT.toFloat())
                        drawFrontArtwork(canvas, project.cards[index], x, spec)
                        canvas.restore()
                    }
                } else {
                    drawFrontArtwork(canvas, project.cards[index], x, spec)
                }
            }
        }
    }

    private fun openingClip(
        project: StudioProject,
        spec: RendererSpec,
        index: Int,
        frame: Int,
    ): Pair<Float, Float>? {
        if (index !in 0..3 || frame >= spec.continuousStartFrame) return null
        val local = frame - RibbonTimeline.cardStartFrame(project, spec, index)
        val left = motionTrack(spec, "ribbon.open.$index.clip.left", local)
        val right = motionTrack(spec, "ribbon.open.$index.clip.right", local)
        if (left == null && right == null) return null
        val slotLeft = index * spec.slotPitch
        return (left ?: slotLeft) to (right ?: slotLeft + spec.slotPitch)
    }

    private fun positionsForFrame(project: StudioProject, frame: Int, spec: RendererSpec): Map<Int, Float> {
        val result = linkedMapOf<Int, Float>()
        if (frame >= spec.continuousStartFrame && project.cards.size > 4) {
            val exact = exactScroll(spec, frame)
            val step = RibbonTimeline.continuousStepFrames(project, spec).coerceAtLeast(1)
            val scroll = exact ?: ((frame - spec.continuousStartFrame).toFloat() / step * spec.slotPitch)
            val firstVisible = ((scroll / spec.slotPitch).toInt() - 1).coerceAtLeast(0)
            val lastVisible = (((scroll + REFERENCE_WIDTH) / spec.slotPitch).toInt() + 1)
                .coerceAtMost(project.cards.lastIndex)
            if (firstVisible <= lastVisible) {
                for (index in firstVisible..lastVisible) {
                    val x = index * spec.slotPitch - scroll
                    if (x > -spec.slotPitch && x < REFERENCE_WIDTH + spec.slotPitch) result[index] = x
                }
            }
            return result
        }

        var active = -1
        for (index in 0 until min(4, project.cards.size)) {
            val start = RibbonTimeline.cardStartFrame(project, spec, index)
            if (frame >= start) active = index
        }
        if (active < 0) return result
        for (index in 0 until active) result[index] = index * spec.slotPitch
        val local = frame - RibbonTimeline.cardStartFrame(project, spec, active)
        val hasExactClip = spec.track("ribbon.open.$active.clip.left", local) != null ||
            spec.track("ribbon.open.$active.clip.right", local) != null
        val exactX = motionTrack(spec, "ribbon.open.$active.card.x", local)
        val progress = bodyProgress(spec, local)
        result[active] = if (hasExactClip) {
            active * spec.slotPitch
        } else exactX ?: if (active == 0) {
            lerp(-spec.slotPitch, 0f, progress)
        } else {
            lerp((active - 1) * spec.slotPitch, active * spec.slotPitch, progress)
        }
        return result
    }

    /**
 * Gentle centred temporal filtering for measured Ribbon motion.
 *
 * The 20/60/20 kernel preserves constant velocity while removing visible
 * single-frame quantisation without adding directional lag.
 */
private fun motionTrack(spec: RendererSpec, target: String, frame: Int): Float? {
    val centre = spec.track(target, frame) ?: return null
    if (spec.precisionMode == "frame-exact") return centre
    val previous = spec.track(target, frame - 1) ?: centre
    val next = spec.track(target, frame + 1) ?: centre
    return previous * 0.20f + centre * 0.60f + next * 0.20f
}

    private fun frameBackgroundColor(spec: RendererSpec, frame: Int): Int {
        val gray = motionTrack(spec, "ribbon.background.gray", frame)
        val r = motionTrack(spec, "ribbon.background.r", frame)
        val g = motionTrack(spec, "ribbon.background.g", frame)
        val b = motionTrack(spec, "ribbon.background.b", frame)
        if (gray == null && r == null && g == null && b == null) return spec.backgroundColor
        val fallback = gray ?: 0f
        return Color.rgb(
            (r ?: fallback).roundToInt().coerceIn(0, 255),
            (g ?: fallback).roundToInt().coerceIn(0, 255),
            (b ?: fallback).roundToInt().coerceIn(0, 255),
        )
    }

    private fun exactScroll(spec: RendererSpec, frame: Int): Float? {
        if (frame < spec.continuousStartFrame) return null
        val segment = (frame - spec.continuousStartFrame) / SCROLL_TRACK_SIZE
        return motionTrack(spec, "ribbon.scroll.$segment", frame)
    }

    private fun bodyProgress(spec: RendererSpec, localFrame: Int): Float {
        motionTrack(spec, "ribbon.body.progress", localFrame)?.let { return it.coerceIn(0f, 1f) }
        val p = (localFrame.toFloat() / spec.bodySlideFrames.coerceAtLeast(1)).coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    private fun drawCardBody(canvas: Canvas, project: StudioProject, card: StudioCard, slotX: Float, spec: RendererSpec) {
        val left = slotX + spec.bodyInset
        val right = left + spec.bodyWidth
        val hasTitle = card.title.isNotBlank()
        val hasDescription = card.description.isNotBlank()
        val canonicalDescriptionHeight = REFERENCE_HEIGHT - spec.descriptionTop
        val descriptionHeight = if (hasDescription) canonicalDescriptionHeight else 0f
        val titleHeight = if (hasTitle) spec.titleHeight else 0f
        val imageBottom = (REFERENCE_HEIGHT - descriptionHeight - titleHeight).coerceAtLeast(1f)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 105, 211)
        canvas.drawRect(left, 0f, right, imageBottom, paint)
        if (!card.imageLayer.equals("front", ignoreCase = true)) {
            drawArtwork(canvas, card, RectF(left, 0f, right, imageBottom))
        }

        var cursor = imageBottom
        if (hasTitle) {
            paint.color = spec.titleBackgroundColor
            canvas.drawRect(left, cursor, right, cursor + titleHeight, paint)
            drawFittedText(
                canvas,
                card.title,
                RectF(left + 12f, cursor + 2f, right - 12f, cursor + titleHeight - 2f),
                spec.titleTextColor,
                spec.titleTextSize,
                22f,
                2,
                true,
                project,
            )
            cursor += titleHeight
        }
        if (hasDescription) {
            paint.color = spec.descriptionBackgroundColor
            canvas.drawRect(left, cursor, right, REFERENCE_HEIGHT.toFloat(), paint)
            drawFittedText(
                canvas,
                card.description,
                RectF(left + 17f, cursor + 6f, right - 17f, REFERENCE_HEIGHT - 6f),
                spec.descriptionTextColor,
                spec.descriptionTextSize,
                12f,
                4,
                false,
                project,
            )
        }
    }

    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec) {
        val hasTitle = card.title.isNotBlank()
        val hasDescription = card.description.isNotBlank()
        val descriptionHeight = if (hasDescription) REFERENCE_HEIGHT - spec.descriptionTop else 0f
        val titleHeight = if (hasTitle) spec.titleHeight else 0f
        val imageBottom = (REFERENCE_HEIGHT - descriptionHeight - titleHeight).coerceAtLeast(1f)
        drawArtwork(
            canvas,
            card,
            RectF(slotX + spec.bodyInset, 0f, slotX + spec.bodyInset + spec.bodyWidth, imageBottom),
        )
    }

    private fun drawArtwork(canvas: Canvas, card: StudioCard, destination: RectF) {
        val bitmap = loadImage(card.image) ?: return
        val leftCrop = card.imageCropLeft.coerceIn(0.0, 0.95)
        val topCrop = card.imageCropTop.coerceIn(0.0, 0.95)
        val rightCrop = card.imageCropRight.coerceIn(0.0, 0.95)
        val bottomCrop = card.imageCropBottom.coerceIn(0.0, 0.95)
        val srcLeft = (bitmap.width * leftCrop).roundToInt().coerceIn(0, bitmap.width - 1)
        val srcTop = (bitmap.height * topCrop).roundToInt().coerceIn(0, bitmap.height - 1)
        val srcRight = (bitmap.width * (1.0 - rightCrop)).roundToInt().coerceIn(srcLeft + 1, bitmap.width)
        val srcBottom = (bitmap.height * (1.0 - bottomCrop)).roundToInt().coerceIn(srcTop + 1, bitmap.height)
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

    private fun drawOpeningCredits(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        if (!project.creditsEnabled || frame >= spec.continuousStartFrame) return
        var active = -1
        for (index in 0 until min(4, project.cards.size)) {
            if (frame >= RibbonTimeline.cardStartFrame(project, spec, index)) active = index
        }
        if (active < 0) return
        val local = frame - RibbonTimeline.cardStartFrame(project, spec, active)
        val p = bodyProgress(spec, local)
        val x = motionTrack(spec, "ribbon.credits.x", frame) ?: when (active) {
            0 -> lerp(REFERENCE_WIDTH.toFloat(), 1440f, p)
            1, 2 -> 1440f
            3 -> lerp(1440f, REFERENCE_WIDTH.toFloat(), p)
            else -> return
        }
        if (x >= REFERENCE_WIDTH) return

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(29, 29, 29)
        canvas.drawRect(x, 0f, x + spec.bodyWidth, REFERENCE_HEIGHT.toFloat(), paint)
        val cx = x + spec.bodyWidth / 2f
        drawSimpleMultiline(
            canvas,
            tagText(spec, "ribbon.credits.blurb", "The values presented are estimates\nfrom publicly available\nsources. Individual results may\nvary depending\non concentration, temperature,\nexposure time, and\nother factors. Do not attempt\nany experiments."),
            cx,
            22f,
            17f,
            Color.WHITE,
            false,
            24f,
            project,
        )
        paint.color = Color.rgb(180, 180, 180)
        canvas.drawRect(x + 47f, 200f, x + spec.bodyWidth - 47f, 202f, paint)
        drawCenteredText(canvas, "Credits", cx, 286f, 44f, Color.WHITE, true, project)

        val rows = listOf(
            "Lead Research & Sourcing" to "Ahmed",
            "Independent Fact Check" to "Alex Lambert",
            "Lead Graphic Designer" to "Jack H",
            "Edit & Post-Production" to "Alex Pacheco",
            "Thumbnail Designer" to "Diego Garcia",
            "Video Idea & Quality Check" to "Ideaguys.co",
        )
        var y = 370f
        rows.forEach { (label, value) ->
            drawCenteredText(canvas, label, cx, y, 18f, Color.WHITE, false, project)
            drawCenteredText(canvas, value, cx, y + 28f, 18f, Color.WHITE, true, project)
            y += 83f
        }
        drawSimpleMultiline(
            canvas,
            "DISCLAIMER:\nTHIS VIDEO IS BASED ON\nCOMMUNITY DISCUSSIONS\nAND RELEVANT SOURCES.",
            cx,
            986f,
            12f,
            Color.rgb(220, 220, 220),
            false,
            14f,
            project,
        )
    }

    private fun drawBadge(
        canvas: Canvas,
        project: StudioProject,
        index: Int,
        cardX: Float,
        globalFrame: Int,
        spec: RendererSpec,
    ) {
        if (!project.showBadges) return
        val card = project.cards[index]
        if (card.value.isBlank() && card.badgeHeader.isBlank()) return
        val start = RibbonTimeline.cardStartFrame(project, spec, index)
        val local = globalFrame - start

        val age: Float
        val matrix = Matrix()
        if (index < 4) {
            val exactPrefix = "ribbon.open.$index"
            val explicitVisibility = motionTrack(spec, "$exactPrefix.visible", local)
            if (explicitVisibility != null) {
                if (explicitVisibility <= 0.001f) return
            } else if (local < OPENING_BADGE_FIRST_FRAME) return
            age = motionTrack(spec, "$exactPrefix.age", local)
                ?: ((local.coerceAtMost(OPENING_BADGE_FINAL_FRAME) - OPENING_BADGE_FIRST_FRAME).toFloat() /
                    (OPENING_BADGE_FINAL_FRAME - OPENING_BADGE_FIRST_FRAME)) * BADGE_ENTRY_AGE

            // Source-exact bundles may provide a different frame-addressed affine
            // path for every opening badge. Older Ribbon bundles keep their shared path.
            val prefix = if (spec.track("$exactPrefix.m00", local) != null) exactPrefix else "ribbon.open"
            val values = floatArrayOf(
                motionTrack(spec, "$prefix.m00", local) ?: 1f,
                motionTrack(spec, "$prefix.m01", local) ?: 0f,
                motionTrack(spec, "$prefix.tx", local) ?: 0f,
                motionTrack(spec, "$prefix.m10", local) ?: 0f,
                motionTrack(spec, "$prefix.m11", local) ?: 1f,
                motionTrack(spec, "$prefix.ty", local) ?: 0f,
                0f, 0f, 1f,
            )
            matrix.setValues(values)
        } else {
            if (local < spec.laterBadgeFallStartFrame) return
            age = (local - spec.laterBadgeFallStartFrame).toFloat() / 103f * 2.25f
            matrix.setTranslate(0f, motionTrack(spec, "ribbon.card.$index.badge.y", local) ?: motionTrack(spec, "ribbon.later.badge.y", local) ?: 0f)
        }

        val stageScale = (motionTrack(spec, "ribbon.card.$index.badge.scale", local) ?: badgeDeemphasisScale(project, index, globalFrame, spec)) * spec.badgeScale
        canvas.save()
        canvas.translate(cardX, 0f)
        canvas.concat(matrix)
        canvas.scale(stageScale, stageScale, spec.badgeCenterX, spec.badgeCenterY)
        drawBadgeSource(canvas, project, card, age, spec, index, local)
        canvas.restore()
    }

    private fun badgeDeemphasisScale(project: StudioProject, index: Int, globalFrame: Int, spec: RendererSpec): Float {
        fun legacyTrigger(nextIndex: Int): Float {
            val start = RibbonTimeline.cardStartFrame(project, spec, nextIndex)
            return if (nextIndex < 4) start + 99f else start.toFloat()
        }
        fun measuredStart(stageIndex: Int): Float? = if (stageIndex in 0..3) {
            spec.track("ribbon.open.$stageIndex.deemphasis.1.start", 0)
        } else null

        var scale = 1f
        if (index + 1 < project.cards.size) {
            val firstStart = measuredStart(index) ?: legacyTrigger(index + 1)
            val p = easeInOutCubic((globalFrame - firstStart) / 60f)
            scale = lerp(1f, 272f / 298f, p)
        }
        if (index + 2 < project.cards.size) {
            val secondStart = measuredStart(index + 1) ?: legacyTrigger(index + 2)
            val p = easeInOutCubic((globalFrame - secondStart) / 60f)
            if (p > 0f) scale = lerp(272f / 298f, 248f / 298f, p)
        }
        return scale
    }

    private fun drawBadgeSource(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        age: Float,
        spec: RendererSpec,
        index: Int,
        local: Int,
    ) {
        val path = badgePath

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(115, 0, 0, 0)
        paint.maskFilter = badgeShadowBlur
        canvas.save()
        canvas.translate(6f, 9f)
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.maskFilter = null

        paint.color = spec.badgeColor
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(
            145,
            Color.red(spec.badgeDarkColor),
            Color.green(spec.badgeDarkColor),
            Color.blue(spec.badgeDarkColor),
        )
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL

        drawRibbonBadgeText(canvas, project, card, age, spec, index, local)
        drawRibbonShine(canvas, age, path, spec, index, local)
    }

    private fun drawRibbonBadgeText(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        age: Float,
        spec: RendererSpec,
        cardIndex: Int,
        local: Int,
    ) {
        val lines = valueLines(card.value)
        val header = card.badgeHeader.trim().uppercase()
        val layout: List<Triple<String, Float, Float>> = when {
            header.isNotBlank() && lines.size <= 1 -> listOf(
                Triple(header, 118f, 34f),
                Triple(lines.firstOrNull().orEmpty(), 225f, 78f),
            )
            header.isNotBlank() -> listOf(
                Triple(header, 94f, 32f),
                Triple(lines.firstOrNull().orEmpty(), 195f, 84f),
                Triple(lines.drop(1).joinToString(" "), 292f, 40f),
            )
            lines.size <= 1 -> listOf(Triple(lines.firstOrNull().orEmpty(), 219f, 72f))
            else -> listOf(
                Triple(lines[0], 168f, 72f),
                Triple(lines.drop(1).joinToString(" "), 250f, 40f),
            )
        }
        layout.forEachIndexed { index, item ->
            val text = item.first
            if (text.isBlank()) return@forEachIndexed
            val startAge = 0.90f + index * 0.10f
            val prefix = if (cardIndex < 4) "ribbon.open.$cardIndex" else "ribbon.card.$cardIndex"
            val groupProgress = motionTrack(spec, "$prefix.text.progress", local)
            val exactProgress = motionTrack(spec, "$prefix.text.$index.progress", local) ?: groupProgress
            val progress = (exactProgress ?: ((age - startAge) / 0.42f)).coerceIn(0f, 1f)
            if (progress <= 0f) return@forEachIndexed
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            val exactOffset = motionTrack(spec, "$prefix.text.$index.y", local)
                ?: motionTrack(spec, "$prefix.text.y", local)
            val y = item.second + (exactOffset ?: (textLandingOffset(age) - (1f - eased) * 112f))
            val exactAlpha = motionTrack(spec, "$prefix.text.$index.alpha", local)
                ?: motionTrack(spec, "$prefix.text.alpha", local)
            val alpha = (255f * (exactAlpha ?: (progress * 1.75f)).coerceIn(0f, 1f)).roundToInt()
            val exactBlur = motionTrack(spec, "$prefix.text.$index.blur", local)
                ?: motionTrack(spec, "$prefix.text.blur", local)
            var size = item.third
            textPaint.typeface = ProjectFontResolver.resolve(project, boldTypeface, Typeface.BOLD)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = size
            while (textPaint.measureText(text) > 264f && size > 18f) {
                size -= 2f
                textPaint.textSize = size
            }

            if (progress < 0.92f) {
                val trailLength = (1f - progress) * 76f
                for (trail in 8 downTo 1) {
                    val fraction = trail / 8f
                    val trailAlpha = (alpha * (1f - fraction) * 0.18f).roundToInt()
                    if (trailAlpha <= 0) continue
                    textPaint.color = Color.argb(trailAlpha, 255, 255, 255)
                    textPaint.maskFilter = BlurMaskFilter(
                        max(0.2f, exactBlur ?: ((1f - progress) * 5.8f)),
                        BlurMaskFilter.Blur.NORMAL,
                    )
                    drawCenteredBaselineText(canvas, text, spec.badgeCenterX, y - trailLength * fraction, textPaint)
                }
            }
            textPaint.maskFilter = null
            textPaint.color = Color.argb((alpha * 0.42f).roundToInt(), 20, 20, 20)
            drawCenteredBaselineText(canvas, text, spec.badgeCenterX + 3f, y + 5f, textPaint)
            textPaint.color = Color.argb(alpha, 255, 255, 255)
            textPaint.maskFilter = when {
                exactBlur != null && exactBlur > 0.05f -> BlurMaskFilter(exactBlur, BlurMaskFilter.Blur.NORMAL)
                exactBlur != null -> null
                progress < 0.96f -> BlurMaskFilter(max(0.2f, (1f - progress) * 5.8f), BlurMaskFilter.Blur.NORMAL)
                else -> null
            }
            drawCenteredBaselineText(canvas, text, spec.badgeCenterX, y, textPaint)
            textPaint.maskFilter = null
        }
    }

    private fun textLandingOffset(age: Float): Float = when {
        age < 0.90f -> 0f
        age < 1.15f -> lerp(0f, 40f, smoothstep((age - 0.90f) / 0.25f))
        age < 1.55f -> 40f
        age < 1.85f -> lerp(40f, 18f, smoothstep((age - 1.55f) / 0.30f))
        age < 2.30f -> lerp(18f, 0f, smoothstep((age - 1.85f) / 0.45f))
        else -> 0f
    }

    private fun drawRibbonShine(
        canvas: Canvas,
        age: Float,
        badge: Path,
        spec: RendererSpec,
        index: Int,
        local: Int,
    ) {
        val exactProgress = if (index < 4) {
            motionTrack(spec, "ribbon.open.$index.shine.progress", local)
        } else {
            motionTrack(spec, "ribbon.card.$index.shine.progress", local) ?: motionTrack(spec, "ribbon.later.shine.progress", local)
        }
        val exactAlpha = if (index < 4) {
            motionTrack(spec, "ribbon.open.$index.shine.alpha", local)
        } else {
            motionTrack(spec, "ribbon.card.$index.shine.alpha", local) ?: motionTrack(spec, "ribbon.later.shine.alpha", local)
        }
        val referenceLocked = exactProgress != null && exactAlpha != null
        val correctedHandRenderer = referenceLocked || spec.tags.contains("badge-reference-lock-v2")

        val progress: Float
        val alpha: Float
        if (referenceLocked) {
            // These are the measured frame values from the reference. Do not pass them
            // through smoothstep: that was the reason the old shine looked too silky.
            progress = exactProgress!!.coerceIn(0f, 1f)
            alpha = exactAlpha!!.coerceIn(0f, 1f)
        } else if (correctedHandRenderer) {
            // Later hand-dissolve badges use the corrected bundle's short shine clock.
            // shineStartFrame is relative to the fall animation for those cards.
            val shineClock = if (index < 4) local else local - spec.laterBadgeFallStartFrame
            val raw = (shineClock - spec.shineStartFrame).toFloat() / spec.shineFrames.coerceAtLeast(1)
            if (raw <= 0f || raw >= 1f) return
            progress = raw.coerceIn(0f, 1f)
            alpha = (1f - abs(progress * 2f - 1f)).coerceIn(0f, 1f)
        } else {
            // Preserve the legacy Ribbon fallback for bundles without exact shine data.
            val raw = (age - 2.18f) / 0.72f
            if (raw <= 0f || raw >= 1f) return
            progress = smoothstep(raw)
            alpha = 1f
        }
        if (alpha <= 0.001f) return

        val topX = lerp(130f, 420f, progress)
        val bottomX = topX - 205f
        // Broad, translucent sweep: thicker than before without becoming a white streak.
        val broadHalfWidth = 40f
        val coreHalfWidth = if (correctedHandRenderer) 7f else 5f

        canvas.save()
        canvas.clipPath(badge)
        shineBroadPath.reset()
        shineBroadPath.moveTo(topX - broadHalfWidth, -80f)
        shineBroadPath.lineTo(topX + broadHalfWidth, -80f)
        shineBroadPath.lineTo(bottomX + broadHalfWidth, 500f)
        shineBroadPath.lineTo(bottomX - broadHalfWidth, 500f)
        shineBroadPath.close()
        paint.color = Color.argb(((if (correctedHandRenderer) 34f else 48f) * alpha).roundToInt().coerceIn(0, 255), 255, 255, 255)
        paint.maskFilter = if (correctedHandRenderer) exactShineBroadBlur else legacyShineBroadBlur
        canvas.drawPath(shineBroadPath, paint)

        shineCorePath.reset()
        shineCorePath.moveTo(topX - coreHalfWidth, -80f)
        shineCorePath.lineTo(topX + coreHalfWidth, -80f)
        shineCorePath.lineTo(bottomX + coreHalfWidth, 500f)
        shineCorePath.lineTo(bottomX - coreHalfWidth, 500f)
        shineCorePath.close()
        paint.color = Color.argb(((if (correctedHandRenderer) 60f else 82f) * alpha).roundToInt().coerceIn(0, 255), 255, 255, 255)
        paint.maskFilter = if (correctedHandRenderer) exactShineCoreBlur else legacyShineCoreBlur
        canvas.drawPath(shineCorePath, paint)
        paint.maskFilter = null
        canvas.restore()
    }

    private fun valueLines(value: String): List<String> {
        val words = value.trim().uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        if (words.size == 1) return words
        return listOf(words.first(), words.drop(1).joinToString(" "))
    }

    private fun drawOutro(canvas: Canvas, project: StudioProject, frame: Int, contentEnd: Int, spec: RendererSpec) {
        val local = frame - contentEnd
        val fadeStart = spec.endWipeFrames + spec.endRiseFrames + spec.endHoldFrames
        val blackStart = fadeStart + spec.fadeFrames
        if (local >= blackStart) {
            canvas.drawColor(Color.BLACK)
            return
        }

        drawOutroAnchor(canvas, project, (contentEnd - 1).coerceAtLeast(0), local, spec)
        if (local < spec.endWipeFrames) {
            val coverY = motionTrack(spec, "ribbon.outro.cover.y", local)
                ?: (REFERENCE_HEIGHT * (local.toFloat() / spec.endWipeFrames.coerceAtLeast(1))).coerceIn(0f, REFERENCE_HEIGHT.toFloat())
            paint.color = frameBackgroundColor(spec, frame)
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, 1440f, coverY, paint)
            return
        }

        paint.color = frameBackgroundColor(spec, frame)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, 1440f, REFERENCE_HEIGHT.toFloat(), paint)

        motionTrack(spec, "ribbon.outro.group.y", local)?.let { drawEndGroup(canvas, it) }
        drawActionBar(canvas, local)

        if (local >= fadeStart) {
            val remaining = motionTrack(spec, "ribbon.outro.fade.alpha", local)
                ?: (255f * (1f - (local - fadeStart).toFloat() / spec.fadeFrames.coerceAtLeast(1))).coerceIn(0f, 255f)
            paint.color = Color.argb((255f - remaining).roundToInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawRect(0f, 0f, REFERENCE_WIDTH.toFloat(), REFERENCE_HEIGHT.toFloat(), paint)
        }
    }

    private fun drawOutroAnchor(
        canvas: Canvas,
        project: StudioProject,
        settledFrame: Int,
        local: Int,
        spec: RendererSpec,
    ) {
        if (project.cards.isEmpty()) return
        val index = project.cards.lastIndex
        val defaultX = if (project.cards.size >= 4) 3f * spec.slotPitch else index * spec.slotPitch
        val x = motionTrack(spec, "ribbon.outro.card.x", local) ?: defaultX
        drawCardBody(canvas, project, project.cards[index], x, spec)
        drawBadge(canvas, project, index, x, settledFrame, spec)
        if (project.cards[index].imageLayer.equals("front", ignoreCase = true)) {
            drawFrontArtwork(canvas, project.cards[index], x, spec)
        }
    }

    private fun drawEndGroup(canvas: Canvas, top: Float) {
        val red = Color.rgb(212, 9, 10)
        paint.style = Paint.Style.FILL
        paint.color = red
        val boxes = listOf(
            RectF(40f, top + 210f, 689f, top + 669f) to "BEST VIDEO FOR YOU",
            RectF(750f, top + 210f, 1400f, top + 669f) to "NEWEST VIDEO",
        )
        boxes.forEach { (box, label) ->
            canvas.drawRoundRect(box, 18f, 18f, paint)
            drawCenteredText(canvas, label, box.centerX(), box.top + 33f, 28f, Color.WHITE, true)
        }
        paint.color = Color.rgb(81, 77, 67)
        val credit = RectF(468f, top + 741f, 970f, top + 1010f)
        canvas.drawRoundRect(credit, 22f, 22f, paint)
        drawCenteredText(canvas, "Video Made By", credit.centerX(), credit.top + 36f, 24f, Color.WHITE, true)
        val rows = listOf(
            "Lead Research & Sourcing     Lead Graphic Designer",
            "Ahmed                       Jack H",
            "Independent Fact Check      Edit & Post-Production",
            "Alex Lambert                 Alex Pacheco",
            "Thumbnail Designer           Video Idea & Quality Check",
            "Diego Garcia                 Ideaguys.co",
        )
        var y = credit.top + 82f
        rows.forEach { line ->
            drawCenteredText(canvas, line, credit.centerX(), y, 12f, Color.rgb(244, 244, 244), false)
            y += 25f
        }
    }

    private fun drawActionBar(canvas: Canvas, local: Int) {
        val bounds = sampleBounds(ACTION_BAR_KEYS, local) ?: return
        val x = bounds[0].toFloat()
        val y = bounds[1].toFloat()
        val w = bounds[2].toFloat()
        val h = bounds[3].toFloat()
        paint.color = Color.rgb(236, 236, 236)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), min(24f, h / 4f), min(24f, h / 4f), paint)

        val subscribe = sampleBounds(SUBSCRIBE_KEYS, local)
        val liked = local >= 197
        val subscribed = local >= 263
        val bellActive = local >= 317
        if (subscribe != null) {
            val sx = subscribe[0].toFloat()
            val sy = subscribe[1].toFloat()
            val sw = subscribe[2].toFloat()
            val sh = subscribe[3].toFloat()
            paint.color = if (subscribed) Color.rgb(83, 83, 83) else Color.rgb(253, 67, 69)
            canvas.drawRoundRect(RectF(sx, sy, sx + sw, sy + sh), min(8f, sh / 5f), min(8f, sh / 5f), paint)
            if (sh >= 28f && sw >= 90f) {
                drawCenteredText(canvas, if (subscribed) "Subscribed" else "Subscribe", sx + sw / 2f, sy + sh / 2f, sh * 0.47f, Color.WHITE, true)
            }
        }

        val iconProgress = smoothstep((local - 86) / 26f)
        val bellProgress = smoothstep((local - 88) / 16f)
        drawThumbIcon(canvas, 516f, 58f, iconProgress, false, if (liked) Color.rgb(33, 150, 243) else Color.rgb(38, 38, 38))
        drawThumbIcon(canvas, 607f, 58f, iconProgress, true, Color.rgb(38, 38, 38))
        drawBellIcon(canvas, 925f, 61f, bellProgress, bellActive)

        if (local >= 102) {
            paint.strokeWidth = 4f
            paint.color = Color.rgb(32, 32, 32)
            canvas.drawLine(508f, 138f, 684f, 138f, paint)
            if (liked) {
                paint.color = Color.rgb(33, 150, 243)
                val blueP = smoothstep((local - 197) / 20f)
                canvas.drawLine(508f, 138f, lerp(508f, 684f, blueP), 138f, paint)
            }
        }
        drawCursor(canvas, local)
    }

    private fun drawCursor(canvas: Canvas, local: Int) {
        if (local !in 155..348) return
        val point = when {
            local < 190 -> interpolatePoint(local, 155, 190, 485f, 185f, 520f, 92f)
            local < 218 -> interpolatePoint(local, 190, 218, 520f, 92f, 550f, 105f)
            local < 238 -> interpolatePoint(local, 218, 238, 550f, 105f, 795f, 93f)
            local < 267 -> interpolatePoint(local, 238, 267, 795f, 93f, 825f, 96f)
            local < 300 -> interpolatePoint(local, 267, 300, 825f, 96f, 967f, 96f)
            local < 328 -> interpolatePoint(local, 300, 328, 967f, 96f, 982f, 105f)
            else -> interpolatePoint(local, 328, 348, 982f, 105f, 1090f, 190f)
        }
        val x = point.first
        val y = point.second
        val cursor = Path().apply {
            moveTo(x, y)
            lineTo(x + 5f, y + 27f)
            lineTo(x + 12f, y + 19f)
            lineTo(x + 20f, y + 32f)
            lineTo(x + 25f, y + 29f)
            lineTo(x + 17f, y + 16f)
            lineTo(x + 28f, y + 15f)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawPath(cursor, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(45, 45, 45)
        canvas.drawPath(cursor, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawThumbIcon(canvas: Canvas, x: Float, y: Float, scale: Float, down: Boolean, color: Int) {
        if (scale <= 0.02f) return
        val s = scale
        val raw = listOf(
            8f to 15f, 17f to 15f, 24f to 4f, 29f to 6f, 28f to 15f,
            39f to 15f, 42f to 20f, 38f to 34f, 17f to 34f, 17f to 38f, 8f to 38f,
        )
        val path = Path()
        raw.forEachIndexed { index, p ->
            var py = p.second
            if (down) py = 42f - py
            val px = x + p.first * s
            val yy = y + py * s
            if (index == 0) path.moveTo(px, yy) else path.lineTo(px, yy)
        }
        path.close()
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
    }

    private fun drawBellIcon(canvas: Canvas, x: Float, y: Float, scale: Float, active: Boolean) {
        if (scale <= 0.02f) return
        val s = scale
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, 3f * s)
        paint.color = Color.rgb(48, 48, 48)
        val oval = RectF(x + 6f * s, y + 8f * s, x + 38f * s, y + 39f * s)
        canvas.drawArc(oval, 195f, 150f, false, paint)
        val path = Path().apply {
            moveTo(x + 8f * s, y + 27f * s)
            lineTo(x + 4f * s, y + 38f * s)
            lineTo(x + 40f * s, y + 38f * s)
            lineTo(x + 36f * s, y + 27f * s)
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x + 22f * s, y + 43f * s, 3f * s, paint)
        if (active) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawArc(RectF(x - 1f, y + 4f, x + 47f, y + 47f), 210f, 36f, false, paint)
            canvas.drawArc(RectF(x - 1f, y + 4f, x + 47f, y + 47f), -66f, 36f, false, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun sampleBounds(keys: Array<IntArray>, local: Int): IntArray? {
        if (local < keys.first()[0]) return null
        if (local >= keys.last()[0]) return keys.last().copyOfRange(1, 5)
        for (index in 1 until keys.size) {
            val right = keys[index]
            if (local <= right[0]) {
                val left = keys[index - 1]
                val p = (local - left[0]).toFloat() / (right[0] - left[0]).coerceAtLeast(1)
                return IntArray(4) { component -> lerp(left[component + 1].toFloat(), right[component + 1].toFloat(), p).roundToInt() }
            }
        }
        return keys.last().copyOfRange(1, 5)
    }

    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        box: RectF,
        color: Int,
        preferredSize: Float,
        minimumSize: Float,
        maxLines: Int,
        bold: Boolean,
        project: StudioProject,
    ) {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return
        var size = preferredSize
        var lines: List<String>
        while (true) {
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            val fallback = if (bold) boldTypeface else regularTypeface
            textPaint.typeface = ProjectFontResolver.resolve(project, fallback, style)
            textPaint.textSize = size
            lines = wrapText(normalized, box.width(), maxLines)
            val lineHeight = size * 1.12f
            if ((lines.size * lineHeight <= box.height() && lines.all { textPaint.measureText(it) <= box.width() }) || size <= minimumSize) break
            size -= 1f
        }
        textPaint.color = color
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = size
        val lineHeight = size * 1.12f
        val total = lineHeight * lines.size
        var y = box.centerY() - total / 2f + lineHeight / 2f
        lines.forEach { line ->
            drawCenteredBaselineText(canvas, line, box.centerX(), y, textPaint)
            y += lineHeight
        }
    }

    private fun wrapText(text: String, width: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (textPaint.measureText(candidate) <= width || current.isBlank()) {
                current = candidate
            } else {
                lines += current
                current = word
                if (lines.size == maxLines - 1) break
            }
        }
        if (current.isNotBlank() && lines.size < maxLines) lines += current
        return lines.take(maxLines)
    }

    private fun drawSimpleMultiline(
        canvas: Canvas,
        text: String,
        x: Float,
        firstY: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        lineHeight: Float,
        project: StudioProject? = null,
    ) {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        val fallback = if (bold) boldTypeface else regularTypeface
        textPaint.typeface = if (project != null) ProjectFontResolver.resolve(project, fallback, style) else fallback
        textPaint.textSize = size
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = color
        var y = firstY
        text.split('\n').forEach { line ->
            canvas.drawText(line, x, y - textPaint.ascent(), textPaint)
            y += lineHeight
        }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        project: StudioProject? = null,
    ) {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        val fallback = if (bold) boldTypeface else regularTypeface
        textPaint.typeface = if (project != null) ProjectFontResolver.resolve(project, fallback, style) else fallback
        textPaint.textSize = size
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = color
        textPaint.maskFilter = null
        drawCenteredBaselineText(canvas, text, x, y, textPaint)
    }

    private fun drawCenteredBaselineText(canvas: Canvas, text: String, x: Float, centerY: Float, p: Paint) {
        val baseline = centerY - (p.ascent() + p.descent()) / 2f
        canvas.drawText(text, x, baseline, p)
    }

    private fun tagText(spec: RendererSpec, key: String, fallback: String): String =
        spec.tags.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.replace("\\n", "\n")
            ?: fallback

    private fun interpolatePoint(local: Int, start: Int, end: Int, x0: Float, y0: Float, x1: Float, y1: Float): Pair<Float, Float> {
        val p = smoothstep((local - start).toFloat() / (end - start).coerceAtLeast(1))
        return lerp(x0, x1, p) to lerp(y0, y1, p)
    }

    private fun smoothstep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun easeInOutCubic(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - ((-2f * x + 2f) * (-2f * x + 2f) * (-2f * x + 2f)) / 2f
    }

    private fun lerp(a: Float, b: Float, p: Float): Float = a + (b - a) * p

    companion object {
        const val REFERENCE_WIDTH = 1920
        const val REFERENCE_HEIGHT = 1080
        private const val SCROLL_TRACK_SIZE = 4096
        private const val OPENING_BADGE_FIRST_FRAME = 35
        private const val OPENING_BADGE_FINAL_FRAME = 120
        private const val BADGE_ENTRY_AGE = 2.90f

        private val ACTION_BAR_KEYS = arrayOf(
            intArrayOf(54, 716, 98, 42, 8), intArrayOf(56, 696, 93, 82, 18),
            intArrayOf(58, 665, 85, 143, 33), intArrayOf(60, 632, 77, 211, 49),
            intArrayOf(62, 580, 64, 314, 75), intArrayOf(64, 563, 60, 349, 84),
            intArrayOf(66, 548, 56, 379, 91), intArrayOf(68, 536, 53, 403, 97),
            intArrayOf(70, 517, 49, 441, 106), intArrayOf(72, 510, 47, 455, 109),
            intArrayOf(74, 503, 45, 469, 113), intArrayOf(76, 498, 44, 479, 115),
            intArrayOf(78, 489, 42, 497, 120), intArrayOf(80, 485, 41, 505, 122),
            intArrayOf(82, 482, 40, 511, 123), intArrayOf(84, 479, 39, 517, 125),
            intArrayOf(86, 474, 38, 526, 127), intArrayOf(88, 473, 38, 529, 127),
            intArrayOf(90, 471, 37, 533, 129), intArrayOf(92, 471, 37, 533, 129),
            intArrayOf(94, 470, 37, 535, 129), intArrayOf(96, 468, 37, 539, 129),
            intArrayOf(98, 468, 37, 539, 130), intArrayOf(100, 468, 37, 540, 130),
            intArrayOf(102, 468, 37, 540, 130),
        )

        private val SUBSCRIBE_KEYS = arrayOf(
            intArrayOf(74, 796, 103, 22, 7), intArrayOf(76, 782, 98, 52, 15),
            intArrayOf(78, 754, 89, 110, 32), intArrayOf(80, 746, 86, 128, 37),
            intArrayOf(82, 740, 84, 140, 40), intArrayOf(84, 735, 82, 150, 44),
            intArrayOf(86, 728, 80, 164, 48), intArrayOf(88, 726, 79, 169, 49),
            intArrayOf(90, 724, 78, 173, 51), intArrayOf(92, 724, 78, 173, 51),
            intArrayOf(94, 722, 78, 177, 51), intArrayOf(96, 720, 78, 182, 52),
            intArrayOf(98, 719, 77, 183, 53), intArrayOf(100, 718, 77, 185, 53),
            intArrayOf(102, 718, 77, 185, 53),
        )
    }
}

object RibbonTimeline {
    fun isRibbon(spec: RendererSpec): Boolean = spec.id.startsWith("ribbon.")

    fun cardStartFrame(project: StudioProject, spec: RendererSpec, index: Int): Int {
        if (index < 4) return spec.openingStarts.getOrElse(index) { index * 120 }
        if (project.autoLength) return spec.continuousStartFrame + (index - 4) * spec.continuousStepFrames
        return spec.continuousStartFrame + (index - 4) * continuousStepFrames(project, spec)
    }

    fun continuousStepFrames(project: StudioProject, spec: RendererSpec): Int {
        if (project.autoLength || project.cards.size <= 4) return spec.continuousStepFrames
        val requestedTotal = (project.customLengthSeconds * project.fps.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
        val requestedContent = (requestedTotal - spec.outroFrames).coerceAtLeast(spec.continuousStartFrame + 1)
        val intervals = (project.cards.size - 4).coerceAtLeast(1)
        return ((requestedContent - spec.continuousStartFrame) / intervals).coerceAtLeast(1)
    }

    fun contentEndFrame(project: StudioProject, spec: RendererSpec): Int {
        val count = project.cards.size
        if (count <= 0) return 0
        if (!project.autoLength) {
            val requested = (project.customLengthSeconds * project.fps.coerceAtLeast(1)).roundToInt() - spec.outroFrames
            val minimum = if (count <= 4) spec.continuousStartFrame
            else spec.continuousStartFrame + (count - 4)
            return max(minimum, requested)
        }
        val canonicalCount = spec.track("ribbon.card_count", 0)?.roundToInt()
        val canonicalEnd = spec.track("ribbon.content_end", 0)?.roundToInt()
        if (canonicalCount != null && canonicalEnd != null && count == canonicalCount) return canonicalEnd
        if (count <= 4) return spec.continuousStartFrame
        return spec.continuousStartFrame + (count - 4) * spec.continuousStepFrames
    }

    fun totalFrameCount(project: StudioProject, spec: RendererSpec): Int = contentEndFrame(project, spec) + spec.outroFrames
}
