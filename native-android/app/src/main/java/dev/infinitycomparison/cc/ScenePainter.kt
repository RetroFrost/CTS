package dev.thedataguys.cc

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class ScenePainter(private val spec: RendererSpec = RendererRuntime.activeSpec) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun drawVideoFrame(canvas: Canvas, project: CompareProject, frameIndex: Int) {
        val save = canvas.save()
        try {
            val sx = canvas.width.toFloat() / project.width.toFloat()
            val sy = canvas.height.toFloat() / project.height.toFloat()
            val scale = min(sx, sy)
            val dx = (canvas.width - project.width * scale) / 2f
            val dy = (canvas.height - project.height * scale) / 2f
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            drawLogicalFrame(canvas, project, frameIndex)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawLogicalFrame(canvas: Canvas, project: CompareProject, frameIndex: Int) {
        val w = project.width.toFloat()
        val h = project.height.toFloat()
        val timeMs = ((frameIndex.toLong() * 1000L) / project.fps.coerceAtLeast(1)).toInt()

        bg.color = spec.backgroundColor
        canvas.drawRect(0f, 0f, w, h, bg)
        drawHeader(canvas, project, w)

        val defaultScroll = defaultScroll(project, timeMs)
        val scroll = (spec.trackValue("scroll", timeMs) ?: defaultScroll).coerceIn(0f, 1f)
        val maxTravel = max(0f, project.items.size * spec.cardSpacing - (h - spec.cardTop - 80f))
        val top = spec.cardTop - scroll * maxTravel

        project.items.forEachIndexed { index, item ->
            val baseY = top + index * spec.cardSpacing
            if (baseY > -spec.cardHeight - 100f && baseY < h + 100f) {
                val entry = openingEntry(index, timeMs)
                val xTrack = spec.trackValue("card.$index.x", timeMs)
                val yTrack = spec.trackValue("card.$index.y", timeMs)
                val alpha = (spec.trackValue("card.$index.alpha", timeMs) ?: 1f).coerceIn(0f, 1f)
                val x = spec.cardSideMargin + (xTrack ?: entry.first)
                val y = baseY + (yTrack ?: entry.second)
                drawCard(canvas, item, x, y, w - spec.cardSideMargin * 2f, index, timeMs, alpha)
            }
        }
    }

    fun drawThumbnail(canvas: Canvas, project: CompareProject) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        bg.color = Color.rgb(17, 13, 12)
        canvas.drawRect(0f, 0f, w, h, bg)

        titlePaint.textSize = 88f
        titlePaint.color = Color.WHITE
        titlePaint.textAlign = Paint.Align.CENTER
        drawFittedText(canvas, project.title.uppercase(), w / 2f, 170f, w - 100f, titlePaint, 44f)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(72, 211, 9, 9) }
        canvas.drawCircle(w * 0.78f, h * 0.32f, 250f, glow)
        canvas.drawCircle(w * 0.20f, h * 0.72f, 210f, glow)

        val big = project.items.firstOrNull() ?: return
        drawThumbnailCard(canvas, big.copy(title = "#1 ${big.title}", value = "???"), 76f, 420f, w - 152f)

        bodyPaint.color = Color.WHITE
        bodyPaint.textSize = 58f
        bodyPaint.textAlign = Paint.Align.CENTER
        drawFittedText(canvas, "THE LAST ONE IS WORSE", w / 2f, h - 290f, w - 110f, bodyPaint, 34f)

        val pill = RectF(115f, h - 235f, w - 115f, h - 135f)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 9, 9) }
        canvas.drawRoundRect(pill, 42f, 42f, pillPaint)
        bodyPaint.textSize = 45f
        drawFittedText(canvas, "WATCH BEFORE YOU EXPORT", w / 2f, h - 171f, w - 270f, bodyPaint, 28f)
    }

    private fun drawHeader(canvas: Canvas, project: CompareProject, w: Float) {
        titlePaint.color = spec.headerColor
        titlePaint.textSize = spec.headerTitleSize
        titlePaint.textAlign = Paint.Align.CENTER
        drawFittedText(canvas, project.title, w / 2f, spec.headerTitleY, w - 110f, titlePaint, 30f)

        smallPaint.color = spec.subtitleColor
        smallPaint.textSize = spec.headerSubtitleSize
        smallPaint.textAlign = Paint.Align.CENTER
        drawFittedText(canvas, project.subtitle, w / 2f, spec.headerSubtitleY, w - 130f, smallPaint, 20f)
    }

    private fun drawCard(
        canvas: Canvas,
        item: CompareItem,
        x: Float,
        y: Float,
        width: Float,
        index: Int,
        timeMs: Int,
        alpha: Float
    ) {
        val layer = canvas.saveLayerAlpha(
            x - 20f,
            y - 20f,
            x + width + 40f,
            y + spec.cardHeight + 40f,
            (alpha * 255f).toInt().coerceIn(0, 255)
        )
        try {
            shadowPaint.color = spec.shadowColor
            cardPaint.color = spec.cardColor
            val shadow = RectF(
                x + spec.cardShadowX,
                y + spec.cardShadowY,
                x + width + spec.cardShadowX,
                y + spec.cardHeight + spec.cardShadowY
            )
            canvas.drawRoundRect(shadow, spec.cardCornerRadius, spec.cardCornerRadius, shadowPaint)
            val card = RectF(x, y, x + width, y + spec.cardHeight)
            canvas.drawRoundRect(card, spec.cardCornerRadius, spec.cardCornerRadius, cardPaint)

            val badgeCx = x + width - spec.badgeRightInset
            val badgeCy = y + spec.badgeTopInset
            val badgePath = hexPath(badgeCx, badgeCy, spec.badgeRadius)
            badgePaint.color = spec.badgeColor
            badgeStroke.color = spec.badgeStrokeColor
            badgeStroke.strokeWidth = spec.badgeStrokeWidth
            canvas.drawPath(badgePath, badgePaint)
            canvas.drawPath(badgePath, badgeStroke)
            drawBadgeShine(canvas, badgePath, badgeCx, badgeCy, index, timeMs)

            bodyPaint.color = spec.headerColor
            bodyPaint.textSize = spec.cardTitleSize
            bodyPaint.textAlign = Paint.Align.LEFT
            val titleMax = max(80f, width - spec.cardTitleX - spec.badgeRightInset - spec.badgeRadius - 35f)
            drawFittedText(canvas, item.title, x + spec.cardTitleX, y + spec.cardTitleY, titleMax, bodyPaint, 22f)

            smallPaint.color = spec.subtitleColor
            smallPaint.textSize = spec.cardSubtitleSize
            smallPaint.textAlign = Paint.Align.LEFT
            drawFittedText(canvas, item.subtitle, x + spec.cardTitleX, y + spec.cardSubtitleY, titleMax, smallPaint, 18f)

            bodyPaint.textAlign = Paint.Align.CENTER
            bodyPaint.color = spec.badgeTextColor
            bodyPaint.textSize = spec.badgeTextSize
            drawFittedText(canvas, item.value, badgeCx, badgeCy + spec.badgeTextSize * 0.35f, spec.badgeRadius * 1.55f, bodyPaint, 14f)
        } finally {
            canvas.restoreToCount(layer)
        }
    }

    private fun drawBadgeShine(canvas: Canvas, badgePath: Path, cx: Float, cy: Float, index: Int, timeMs: Int) {
        if (!spec.shineEnabled) return
        val track = spec.trackValue("badge.$index.shine", timeMs)
        val localMs = timeMs - spec.shineStartMs - index * 90
        val default = if (localMs in 0..spec.shineDurationMs && spec.shineDurationMs > 0) {
            localMs.toFloat() / spec.shineDurationMs.toFloat()
        } else {
            -1f
        }
        val p = track ?: default
        if (p !in 0f..1f) return

        val save = canvas.save()
        try {
            canvas.clipPath(badgePath)
            shinePaint.color = spec.shineColor
            val travel = spec.badgeRadius * 2.8f
            val centerX = cx - travel / 2f + p * travel
            canvas.rotate(-18f, centerX, cy)
            canvas.drawRect(
                centerX - spec.shineWidth / 2f,
                cy - spec.badgeRadius * 1.6f,
                centerX + spec.shineWidth / 2f,
                cy + spec.badgeRadius * 1.6f,
                shinePaint
            )
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawThumbnailCard(canvas: Canvas, item: CompareItem, x: Float, y: Float, width: Float) {
        val height = 500f
        shadowPaint.color = spec.shadowColor
        cardPaint.color = spec.cardColor
        canvas.drawRoundRect(RectF(x + 10f, y + 12f, x + width + 10f, y + height + 12f), 44f, 44f, shadowPaint)
        canvas.drawRoundRect(RectF(x, y, x + width, y + height), 44f, 44f, cardPaint)

        val badgeCx = x + width - 165f
        val badgeCy = y + 150f
        val badgePath = hexPath(badgeCx, badgeCy, 103f)
        badgePaint.color = spec.badgeColor
        badgeStroke.color = spec.badgeStrokeColor
        badgeStroke.strokeWidth = 4f
        canvas.drawPath(badgePath, badgePaint)
        canvas.drawPath(badgePath, badgeStroke)

        bodyPaint.color = spec.headerColor
        bodyPaint.textAlign = Paint.Align.LEFT
        bodyPaint.textSize = 62f
        drawFittedText(canvas, item.title, x + 46f, y + 126f, width - 330f, bodyPaint, 30f)
        smallPaint.color = spec.subtitleColor
        smallPaint.textAlign = Paint.Align.LEFT
        smallPaint.textSize = 38f
        drawFittedText(canvas, item.subtitle, x + 46f, y + 193f, width - 330f, smallPaint, 22f)

        bodyPaint.textAlign = Paint.Align.CENTER
        bodyPaint.color = spec.badgeTextColor
        bodyPaint.textSize = 36f
        drawFittedText(canvas, item.value, badgeCx, badgeCy + 12f, 155f, bodyPaint, 20f)
    }

    private fun hexPath(cx: Float, cy: Float, radius: Float): Path = Path().apply {
        for (i in 0 until 6) {
            val angle = Math.toRadians(60.0 * i - 30.0)
            val px = cx + cos(angle).toFloat() * radius
            val py = cy + sin(angle).toFloat() * radius
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }

    private fun openingEntry(index: Int, timeMs: Int): Pair<Float, Float> {
        if (spec.openingDurationMs <= 0 || timeMs >= spec.openingDurationMs) return 0f to 0f
        val p = easeOut((timeMs.toFloat() / spec.openingDurationMs.toFloat()).coerceIn(0f, 1f))
        val y = (1f - p) * spec.openingYOffset
        val x = if (index == spec.specialEntryCard) (1f - p) * spec.specialEntryXOffset else 0f
        return x to y
    }

    private fun defaultScroll(project: CompareProject, timeMs: Int): Float {
        val durationMs = project.seconds * 1000
        val endMs = max(spec.scrollStartMs + 1, durationMs - spec.scrollEndPaddingMs)
        val p = ((timeMs - spec.scrollStartMs).toFloat() / (endMs - spec.scrollStartMs).toFloat()).coerceIn(0f, 1f)
        return easeInOut(p)
    }

    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint,
        minSize: Float
    ) {
        if (text.isEmpty()) return
        val original = paint.textSize
        var size = original
        while (size > minSize && paint.measureText(text) > maxWidth) {
            size -= 1f
            paint.textSize = size
        }
        val finalText = if (paint.measureText(text) <= maxWidth) text else ellipsize(text, maxWidth, paint)
        canvas.drawText(finalText, x, y, paint)
        paint.textSize = original
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        val ellipsis = "…"
        if (paint.measureText(ellipsis) > maxWidth) return ""
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) end--
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x)
    private fun easeInOut(x: Float): Float = x * x * (3f - 2f * x)
}
