package io.github.retrofrost.cts.android.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

internal object ReferenceOverlayRenderer {
    fun drawIntroCredits(canvas: Canvas, width: Int, height: Int, paint: Paint) {
        val cardWidth = width / 4f
        val left = width - cardWidth
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(32, 32, 32)
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.WHITE
        drawCentered(canvas, "The values presented are average milestones", left, cardWidth, height * 0.06f, height * 0.018f, paint)
        paint.color = Color.rgb(190, 190, 190)
        canvas.drawRect(left + cardWidth * 0.12f, height * 0.20f, width - cardWidth * 0.12f, height * 0.202f, paint)
        paint.color = Color.WHITE
        drawCentered(canvas, "Credits", left, cardWidth, height * 0.30f, height * 0.042f, paint, true)
        val lines = listOf(
            "Lead Research & Sourcing", "Independent Fact Check", "Lead Graphic Designer",
            "Edit & Post-Production", "Thumbnail Designer", "Video Idea & Quality Check",
        )
        lines.forEachIndexed { index, line ->
            drawCentered(canvas, line, left, cardWidth, height * (0.39f + index * 0.075f), height * 0.018f, paint)
        }
        paint.color = Color.rgb(200, 200, 200)
        drawCentered(canvas, "DISCLAIMER · COMMUNITY DISCUSSIONS AND SOURCES", left, cardWidth, height * 0.93f, height * 0.011f, paint)
    }

    fun drawOutro(
        canvas: Canvas,
        width: Int,
        height: Int,
        coverProgress: Float,
        contentAlpha: Float,
        paint: Paint,
    ) {
        val overlayRight = width * 0.75f
        if (coverProgress > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(17, 17, 17)
            canvas.drawRect(0f, 0f, overlayRight, height * coverProgress.coerceIn(0f, 1f), paint)
        }
        if (contentAlpha <= 0f) return
        val alpha = (255f * contentAlpha.coerceIn(0f, 1f)).toInt()
        paint.color = Color.argb(alpha, 17, 17, 17)
        canvas.drawRect(0f, 0f, overlayRight, height.toFloat(), paint)

        val margin = width * 0.02f
        val gap = width * 0.025f
        val boxTop = height * 0.17f
        val boxBottom = height * 0.53f
        val boxWidth = (overlayRight - margin * 2 - gap) / 2f
        paint.color = Color.argb(alpha, 224, 0, 0)
        canvas.drawRoundRect(RectF(margin, boxTop, margin + boxWidth, boxBottom), 12f, 12f, paint)
        canvas.drawRoundRect(RectF(margin + boxWidth + gap, boxTop, overlayRight - margin, boxBottom), 12f, 12f, paint)
        paint.color = Color.argb(alpha, 255, 255, 255)
        drawCentered(canvas, "BEST VIDEO FOR YOU", margin, boxWidth, boxTop + height * 0.045f, height * 0.027f, paint, true)
        drawCentered(canvas, "NEWEST VIDEO", margin + boxWidth + gap, boxWidth, boxTop + height * 0.045f, height * 0.027f, paint, true)

        val credits = RectF(overlayRight * 0.32f, height * 0.62f, overlayRight * 0.68f, height * 0.84f)
        paint.color = Color.argb(alpha, 98, 95, 86)
        canvas.drawRoundRect(credits, 12f, 12f, paint)
        paint.color = Color.argb(alpha, 255, 255, 255)
        drawCentered(canvas, "Video Made By", credits.left, credits.width(), credits.top + credits.height() * 0.22f, height * 0.026f, paint, true)
        drawCentered(canvas, "Research · Editing · Design · Quality Check", credits.left, credits.width(), credits.top + credits.height() * 0.58f, height * 0.014f, paint)
    }

    private fun drawCentered(
        canvas: Canvas,
        text: String,
        left: Float,
        width: Float,
        baseline: Float,
        size: Float,
        paint: Paint,
        bold: Boolean = false,
    ) {
        paint.textSize = max(8f, size)
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, left + width / 2f, baseline, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}
