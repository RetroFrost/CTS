package io.github.retrofrost.cts.android.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.retrofrost.cts.android.model.CreditsSettings
import kotlin.math.max

internal object ReferenceOverlayRenderer {
    fun drawIntroCredits(
        canvas: Canvas,
        width: Int,
        height: Int,
        credits: CreditsSettings,
        paint: Paint,
    ) {
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
        drawCentered(canvas, credits.heading, left, cardWidth, height * 0.30f, height * 0.042f, paint, true)
        val lines = credits.lines.lineSequence().filter(String::isNotBlank).take(7).toList()
        lines.forEachIndexed { index, line ->
            drawCentered(canvas, line, left, cardWidth, height * (0.39f + index * 0.075f), height * 0.018f, paint)
        }
        paint.color = Color.rgb(200, 200, 200)
        drawCentered(canvas, credits.footer, left, cardWidth, height * 0.93f, height * 0.011f, paint)
    }

    fun drawOutro(
        canvas: Canvas,
        width: Int,
        height: Int,
        coverProgress: Float,
        contentAlpha: Float,
        contentYOffsetPx: Float?,
        creditsSettings: CreditsSettings,
        paint: Paint,
    ) {
        val overlayRight = width * 0.75f
        if (coverProgress > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(17, 17, 17)
            canvas.drawRect(0f, 0f, overlayRight, height * coverProgress.coerceIn(0f, 1f), paint)
        }
        if (contentAlpha <= 0f) return
        val rise = 1f - (1f - contentAlpha.coerceIn(0f, 1f)).let { it * it * it }
        val yOffset = contentYOffsetPx?.let { it * height / 1080f } ?: height * (1f - rise)
        val alpha = 255
        paint.color = Color.rgb(17, 17, 17)
        canvas.drawRect(0f, 0f, overlayRight, height.toFloat(), paint)

        val scaleX = width / 1920f
        val scaleY = height / 1080f
        val leftBox = RectF(40f * scaleX, 210f * scaleY + yOffset, 689f * scaleX, 669f * scaleY + yOffset)
        val rightBox = RectF(750f * scaleX, 210f * scaleY + yOffset, 1400f * scaleX, 669f * scaleY + yOffset)
        paint.color = Color.argb(alpha, 212, 9, 10)
        canvas.drawRoundRect(leftBox, 18f * scaleX, 18f * scaleY, paint)
        canvas.drawRoundRect(rightBox, 18f * scaleX, 18f * scaleY, paint)
        paint.color = Color.WHITE
        drawCentered(canvas, "BEST VIDEO FOR YOU", leftBox.left, leftBox.width(), leftBox.top + 60f * scaleY, 35f * scaleY, paint, true)
        drawCentered(canvas, "NEWEST VIDEO", rightBox.left, rightBox.width(), rightBox.top + 60f * scaleY, 35f * scaleY, paint, true)

        val credits = RectF(468f * scaleX, 741f * scaleY + yOffset, 970f * scaleX, 1010f * scaleY + yOffset)
        paint.color = Color.rgb(81, 77, 67)
        canvas.drawRoundRect(credits, 22f * scaleX, 22f * scaleY, paint)
        paint.color = Color.WHITE
        drawCentered(canvas, creditsSettings.endingHeading, credits.left, credits.width(), credits.top + 56f * scaleY, 35f * scaleY, paint, true)
        creditsSettings.endingDetails.lineSequence().filter(String::isNotBlank).take(5).forEachIndexed { index, line ->
            drawCentered(canvas, line, credits.left + 18f * scaleX, credits.width() - 36f * scaleX, credits.top + (105f + index * 30f) * scaleY, 18f * scaleY, paint)
        }
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
