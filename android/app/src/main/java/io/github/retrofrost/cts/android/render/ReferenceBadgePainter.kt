package io.github.retrofrost.cts.android.render

import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.timeline.CardPlacement
import kotlin.math.max

/** One badge implementation shared by the Compose preview and bitmap exporter. */
object ReferenceBadgePainter {
    private const val TEXT_START = 0.90f
    private const val TEXT_LINE_DELAY = 0.10f
    private const val TEXT_LINE_SECONDS = 0.42f
    private const val SHINE_START = 1.72f
    private const val SHINE_SECONDS = 0.52f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    @Synchronized
    fun draw(
        canvas: Canvas,
        card: CtsCard,
        model: VisualModel,
        placement: CardPlacement,
        cardLeft: Float,
        cardWidth: Float,
        frameHeight: Float,
    ) {
        if (model == VisualModel.Relationships) {
            drawRelationships(canvas, card, placement, cardLeft, cardWidth, frameHeight)
        } else {
            drawMales(canvas, card, placement, cardLeft, cardWidth, frameHeight)
        }
    }

    private fun drawMales(canvas: Canvas, card: CtsCard, placement: CardPlacement, cardLeft: Float, cardWidth: Float, frameHeight: Float) {
        val sx = cardWidth / 480f
        val sy = frameHeight / 1080f
        val motion = placement.badgeAffine
        canvas.save()
        canvas.translate(cardLeft, 0f)
        canvas.scale(sx, sy)
        canvas.concat(Matrix().apply { setValues(floatArrayOf(motion.m00, motion.m01, motion.tx, motion.m10, motion.m11, motion.ty, 0f, 0f, 1f)) })
        val path = Path().apply {
            moveTo(240f, 32f); lineTo(393f, 116f); lineTo(393f, 289f); lineTo(247f, 375f); lineTo(96f, 289f); lineTo(96f, 118f); close()
        }
        fill.alpha = 255; fill.maskFilter = null; fill.clearShadowLayer(); fill.shader = null
        fill.color = Color.rgb(211, 8, 9); fill.setShadowLayer(8f, 5f, 8f, Color.argb(150, 0, 0, 0)); canvas.drawPath(path, fill)
        fill.clearShadowLayer(); fill.shader = null
        stroke.color = Color.rgb(185, 0, 8); stroke.strokeWidth = 2f; canvas.drawPath(path, stroke)
        if (placement.cardIndex < 4) drawEntryStreak(canvas, path, placement.badgeAgeSeconds)
        val age = placement.badgeAgeSeconds
        val lines = if (card.badgeSecondary.isBlank()) listOf(Triple(card.badgePrimary, 219f, 72f)) else {
            val secondary = splitLabel(card.badgeSecondary).take(2)
            buildList {
                add(Triple(card.badgePrimary, 168f, 72f))
                val firstY = if (secondary.size == 1) 250f else 230f
                secondary.forEachIndexed { index, line -> add(Triple(line.uppercase(), firstY + index * 40f, 40f)) }
            }
        }
        lines.forEachIndexed { index, (value, targetY, size) -> drawAnimatedText(canvas, value, 243.5f, targetY, size, age, index) }
        drawShine(canvas, path, age); text.alpha = 255; canvas.restore()
    }

    private fun drawRelationships(canvas: Canvas, card: CtsCard, placement: CardPlacement, cardLeft: Float, cardWidth: Float, frameHeight: Float) {
        val normalized = placement.badgeRect ?: return
        val badge = RectF(cardLeft + cardWidth * normalized.x, frameHeight * normalized.y, cardLeft + cardWidth * (normalized.x + normalized.width), frameHeight * (normalized.y + normalized.height))
        val cutX = badge.width() * 0.18f; val cutY = badge.height() * 0.18f
        val path = Path().apply {
            moveTo(badge.left + cutX, badge.top); lineTo(badge.right - cutX, badge.top); lineTo(badge.right, badge.top + cutY)
            lineTo(badge.right, badge.bottom - cutY); lineTo(badge.right - cutX, badge.bottom); lineTo(badge.left + cutX, badge.bottom)
            lineTo(badge.left, badge.bottom - cutY); lineTo(badge.left, badge.top + cutY); close()
        }
        fill.alpha = 255; fill.maskFilter = null; fill.clearShadowLayer(); fill.shader = null
        fill.color = Color.rgb(211, 15, 14); fill.setShadowLayer(max(3f, badge.width() * 0.025f), 0f, badge.width() * 0.012f, Color.argb(160, 0, 0, 0)); canvas.drawPath(path, fill)
        fill.clearShadowLayer(); stroke.color = Color.rgb(254, 186, 97); stroke.strokeWidth = max(1f, badge.width() * 0.006f); canvas.drawPath(path, stroke)
        text.alpha = (255 * placement.badgeTextAlpha.coerceIn(0f, 1f)).toInt()
        text.textSize = badge.width() * 0.13f; drawTextShadow(canvas, "1 in", badge.centerX(), badge.top + badge.height() * 0.27f, text); canvas.drawText("1 in", badge.centerX(), badge.top + badge.height() * 0.27f, text)
        text.textSize = badge.width() * 0.30f; val number = relationshipNumber(card.badgePrimary); drawTextShadow(canvas, number, badge.centerX(), badge.top + badge.height() * 0.62f, text); canvas.drawText(number, badge.centerX(), badge.top + badge.height() * 0.62f, text)
        text.textSize = badge.width() * 0.12f; drawTextShadow(canvas, "People", badge.centerX(), badge.top + badge.height() * 0.86f, text); canvas.drawText("People", badge.centerX(), badge.top + badge.height() * 0.86f, text)
        drawShine(canvas, path, placement.badgeAgeSeconds, badge); text.alpha = 255
    }

    private fun drawAnimatedText(canvas: Canvas, value: String, x: Float, targetY: Float, size: Float, age: Float, lineIndex: Int) {
        val progress = ((age - (TEXT_START + lineIndex * TEXT_LINE_DELAY)) / TEXT_LINE_SECONDS).coerceIn(0f, 1f)
        if (progress <= 0f) return
        val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        val y = targetY + textLandingOffset(age) - (1f - eased) * 112f
        val alpha = (255f * (progress * 1.75f).coerceIn(0f, 1f)).toInt(); text.textSize = size
        if (progress < 0.92f) {
            val trailLength = (1f - progress) * 76f
            for (trailIndex in 8 downTo 1) { val fraction = trailIndex / 8f; text.alpha = (alpha * (1f - fraction) * 0.18f).toInt(); canvas.drawText(value, x, y - trailLength * fraction, text) }
        }
        text.alpha = (alpha * 0.42f).toInt(); val originalColor = text.color; text.color = Color.rgb(20, 20, 20); canvas.drawText(value, x + 3f, y + 5f, text); text.color = originalColor; text.alpha = alpha; canvas.drawText(value, x, y, text)
    }

    private fun textLandingOffset(age: Float): Float = when {
        age < 0.90f -> 0f
        age < 1.15f -> lerp(0f, 40f, smoothStep((age - 0.90f) / 0.25f))
        age < 1.55f -> 40f
        age < 1.85f -> lerp(40f, 18f, smoothStep((age - 1.55f) / 0.30f))
        age < 2.30f -> lerp(18f, 0f, smoothStep((age - 1.85f) / 0.45f))
        else -> 0f
    }

    private fun drawEntryStreak(canvas: Canvas, clip: Path, age: Float) {
        if (age !in 0.12f..0.82f) return
        val strength = (smoothStep((age - 0.12f) / 0.16f) * (1f - smoothStep((age - 0.42f) / 0.22f))).coerceIn(0f, 1f)
        if (strength <= 0f) return
        val center = lerp(118f, 154f, smoothStep(age / 0.82f))
        val streak = Path().apply { moveTo(center - 38f, -70f); lineTo(center + 16f, -70f); lineTo(center - 18f, 500f); lineTo(center - 78f, 500f); close() }
        canvas.save(); canvas.clipPath(clip); fill.shader = null; fill.color = Color.argb((116f * strength).toInt(), 255, 255, 255); fill.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL); canvas.drawPath(streak, fill); fill.maskFilter = null; canvas.restore()
    }

    private fun drawShine(canvas: Canvas, clip: Path, age: Float, bounds: RectF? = null) {
        val raw = (age - SHINE_START) / SHINE_SECONDS
        if (raw <= 0f || raw >= 1f) return
        val progress = smoothStep(raw); val sourceLeft = bounds?.left ?: 0f; val sourceTop = bounds?.top ?: 0f; val sourceWidth = bounds?.width() ?: 480f; val sourceHeight = bounds?.height() ?: 430f
        val topX = sourceLeft + sourceWidth * lerp(130f / 480f, 420f / 480f, progress); val bottomX = topX - sourceWidth * (205f / 480f)
        fun band(widthFraction: Float, alpha: Int, blur: Float): Path = Path().apply { val half = sourceWidth * widthFraction; moveTo(topX - half, sourceTop - sourceHeight * 0.2f); lineTo(topX + half, sourceTop - sourceHeight * 0.2f); lineTo(bottomX + half, sourceTop + sourceHeight * 1.2f); lineTo(bottomX - half, sourceTop + sourceHeight * 1.2f); close(); fill.color = Color.argb(alpha, 255, 255, 255); fill.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
        canvas.save(); canvas.clipPath(clip); fill.shader = null; val broad = band(40f / 480f, 48, max(2f, sourceWidth * 12f / 480f)); canvas.drawPath(broad, fill); val core = band(5f / 480f, 82, max(1f, sourceWidth * 2.8f / 480f)); canvas.drawPath(core, fill); fill.maskFilter = null; canvas.restore()
    }

    private fun drawTextShadow(canvas: Canvas, value: String, x: Float, y: Float, paint: Paint) { val originalColor = paint.color; val originalAlpha = paint.alpha; paint.color = Color.rgb(20, 20, 20); paint.alpha = (originalAlpha * 0.42f).toInt(); canvas.drawText(value, x + paint.textSize * 0.04f, y + paint.textSize * 0.07f, paint); paint.color = originalColor; paint.alpha = originalAlpha }
    private fun smoothStep(value: Float): Float { val x = value.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }
    private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount.coerceIn(0f, 1f)
    private fun splitLabel(value: String): List<String> { val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }; if (words.size <= 2) return words; val split = words.indices.drop(1).minByOrNull { index -> kotlin.math.abs(words.take(index).joinToString(" ").length - words.drop(index).joinToString(" ").length) } ?: 1; return listOf(words.take(split).joinToString(" "), words.drop(split).joinToString(" ")) }
    private fun relationshipNumber(value: String): String { val words = value.replace('\n', ' ').split(' ').filter { it.isNotBlank() }; val inIndex = words.indexOfFirst { it.equals("in", ignoreCase = true) }; return words.getOrNull(inIndex + 1) ?: words.firstOrNull { word -> word.any { it.isDigit() } } ?: value.ifBlank { "?" } }
}
