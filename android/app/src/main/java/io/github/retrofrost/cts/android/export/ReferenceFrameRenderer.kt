package io.github.retrofrost.cts.android.export

import android.content.Context
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
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.github.retrofrost.cts.android.layout.CardContentLayout
import io.github.retrofrost.cts.android.layout.CardContentFrames
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import io.github.retrofrost.cts.android.timeline.CardPlacement
import java.io.File
import java.io.FileInputStream
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/** Draws the exact Android reference layout into a Bitmap for MediaCodec export. */
class ReferenceFrameRenderer(
    private val context: Context,
    private val project: CtsProject,
    private val width: Int,
    private val height: Int,
) {
    private val imageCache = mutableMapOf<String, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun render(target: Bitmap, outputTimeSeconds: Float) {
        require(target.width == width && target.height == height)
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        val cardWidth = width / 4f

        if (TimelineEngine.introCreditsVisible(project, outputTimeSeconds)) {
            ReferenceOverlayRenderer.drawIntroCredits(canvas, width, height, paint)
        }
        TimelineEngine.placements(project, outputTimeSeconds).forEach { placement ->
            val card = project.cards.getOrNull(placement.cardIndex) ?: return@forEach
            val cardX = cardWidth * placement.xInCards
            canvas.save()
            canvas.translate(cardX, 0f)
            if (project.model == VisualModel.Relationships) {
                canvas.saveLayerAlpha(
                    0f,
                    0f,
                    cardWidth,
                    height.toFloat(),
                    (255 * placement.bodyReveal.coerceIn(0f, 1f)).toInt(),
                )
            } else {
                canvas.clipRect(0f, 0f, cardWidth * placement.bodyReveal.coerceIn(0f, 1f), height.toFloat())
            }
            drawCardBody(canvas, card, cardWidth, placement)
            if (project.model == VisualModel.Relationships) canvas.restore()
            canvas.restore()
            if (project.showHexagons && placement.badgeVisible) {
                drawBadge(canvas, card, cardX, cardWidth, placement)
            }
        }

        if (project.model == VisualModel.Relationships) {
            ReferenceOverlayRenderer.drawRelationshipsPrelude(
                canvas,
                width,
                height,
                TimelineEngine.relationshipsSourceFrame(project, outputTimeSeconds),
                TimelineEngine.relationshipsDisclaimerAlpha(project, outputTimeSeconds),
                project.showIntro,
                paint,
            )
        }

        val cover = TimelineEngine.outroCoverProgress(project, outputTimeSeconds)
        val content = TimelineEngine.outroContentAlpha(project, outputTimeSeconds)
        if (project.model == VisualModel.Relationships) {
            ReferenceOverlayRenderer.drawRelationshipsOutro(
                canvas,
                width,
                height,
                TimelineEngine.relationshipsOutroLocalFrame(project, outputTimeSeconds),
                content,
                paint,
            )
        } else {
            ReferenceOverlayRenderer.drawOutro(canvas, width, height, cover, content, paint)
        }

        val fade = TimelineEngine.fadeAlpha(project, outputTimeSeconds).coerceIn(0f, 1f)
        if (fade < 0.999f) {
            paint.shader = null
            paint.color = Color.argb(((1f - fade) * 255f).toInt(), 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    fun close() {
        imageCache.values.filterNotNull().forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        imageCache.clear()
    }

    private fun drawCardBody(canvas: Canvas, card: CtsCard, cardWidth: Float, placement: CardPlacement) {
        val frames = if (project.model == VisualModel.Relationships) {
            CardContentFrames(
                image = NormalizedRect(0f, 0f, 475f / 480f, 678f / 1080f),
                title = NormalizedRect(0f, 678f / 1080f, 475f / 480f, 103f / 1080f),
                description = NormalizedRect(0f, 789f / 1080f, 475f / 480f, 291f / 1080f),
            )
        } else CardContentLayout.frames(card)
        val image = frameRect(frames.image, cardWidth)
        val title = frames.title?.let { frameRect(it, cardWidth) }
        val description = frames.description?.let { frameRect(it, cardWidth) }

        val topColor = if (project.model == VisualModel.Relationships) Color.rgb(18, 167, 160) else Color.rgb(19, 141, 219)
        val bottomColor = if (project.model == VisualModel.Relationships) Color.rgb(8, 107, 120) else Color.rgb(11, 116, 190)
        paint.shader = LinearGradient(
            image.left,
            image.top,
            image.left,
            image.bottom,
            intArrayOf(topColor, topColor, bottomColor),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(image, paint)
        paint.shader = null

        loadImage(card.imageSubcard.source)?.let { bitmap ->
            val transform = card.imageSubcard.transform.clamped()
            val destination = RectF(
                image.left + image.width() * transform.x,
                image.top + image.height() * transform.y,
                image.left + image.width() * (transform.x + transform.width),
                image.top + image.height() * (transform.y + transform.height),
            )
            canvas.save()
            canvas.clipRect(image)
            drawCenterCrop(canvas, bitmap, destination)
            canvas.restore()
        }

        title?.let {
            paint.color = if (project.model == VisualModel.Relationships) Color.rgb(245, 245, 243) else Color.rgb(240, 240, 240)
            canvas.drawRect(it, paint)
        }
        description?.let {
            paint.color = if (project.model == VisualModel.Relationships) Color.rgb(47, 47, 47) else Color.rgb(98, 95, 86)
            canvas.drawRect(it, paint)
        }

        val divider = max(2f, cardWidth * 0.008f)
        paint.color = Color.rgb(17, 16, 12)
        canvas.drawRect(0f, 0f, divider, height.toFloat(), paint)
        canvas.drawRect(cardWidth - divider, 0f, cardWidth, height.toFloat(), paint)
        title?.let { canvas.drawRect(0f, it.top, cardWidth, it.top + divider, paint) }
        description?.let { canvas.drawRect(0f, it.top, cardWidth, it.top + divider, paint) }
        canvas.drawRect(0f, height - divider, cardWidth, height.toFloat(), paint)

        val padding = cardWidth * 0.035f
        title?.let {
            drawTextBlock(
                canvas = canvas,
                text = card.title,
                rect = RectF(it.left + padding, it.top + 2f, it.right - padding, it.bottom - 2f),
                color = Color.rgb(16, 16, 16),
                bold = true,
                maximumSize = height * 0.043f,
                minimumSize = height * 0.018f,
                maxLines = 2,
            )
        }
        description?.let {
            drawTextBlock(
                canvas = canvas,
                text = card.description,
                rect = RectF(
                    it.left + padding,
                    it.top + 2f,
                    it.right - padding,
                    it.bottom - 2f,
                ),
                color = Color.WHITE,
                bold = true,
                maximumSize = height * 0.027f,
                minimumSize = height * 0.014f,
                maxLines = 3,
            )
        }

        if (project.model == VisualModel.Relationships) {
            if (placement.artworkReveal < 1f) {
                paint.color = Color.rgb(31, 31, 31)
                canvas.drawRect(
                    image.left,
                    image.top + image.height() * placement.artworkReveal.coerceIn(0f, 1f),
                    image.right,
                    image.bottom,
                    paint,
                )
            }
            if (placement.titleReveal < 1f) {
                title?.let {
                    paint.color = Color.rgb(245, 245, 243)
                    canvas.drawRect(it, paint)
                }
            }
            if (placement.descriptionReveal < 1f) {
                description?.let {
                    paint.color = Color.rgb(47, 47, 47)
                    canvas.drawRect(it, paint)
                }
            }
            paint.color = Color.rgb(234, 127, 28)
            description?.let { canvas.drawRect(0f, it.top - max(2f, cardWidth * 0.0105f), cardWidth, it.top, paint) }
        }
    }

    private fun drawBadge(
        canvas: Canvas,
        card: CtsCard,
        cardX: Float,
        cardWidth: Float,
        placement: CardPlacement,
    ) {
        val settle = placement.badgeSettle.coerceIn(0f, 1.25f)
        val base = frameRect(placement.badgeRect ?: BADGE_FRAME, cardWidth).apply { offset(cardX, 0f) }
        val scale = 1.42f - 0.42f * settle
        val translation = -base.height() * 0.42f * (1f - settle)
        val cx = base.centerX()
        val cy = base.centerY() + translation
        val badge = RectF(
            cx - base.width() * scale / 2f,
            cy - base.height() * scale / 2f,
            cx + base.width() * scale / 2f,
            cy + base.height() * scale / 2f,
        )
        if (project.model == VisualModel.Relationships) {
            drawRelationshipsBadge(canvas, card, base, placement.badgeTextAlpha)
            return
        }
        val path = hexagon(badge)

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(120, 0, 0, 0)
        paint.setShadowLayer(max(3f, cardWidth * 0.025f), 0f, cardWidth * 0.012f, Color.argb(160, 0, 0, 0))
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()

        paint.shader = LinearGradient(
            badge.left,
            badge.top,
            badge.left,
            badge.bottom,
            intArrayOf(Color.rgb(235, 9, 9), Color.rgb(224, 0, 0), Color.rgb(213, 0, 0)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, cardWidth * 0.003f)
        paint.color = Color.rgb(255, 69, 69)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL

        if (settle < 0.94f) {
            val progress = settle / 0.94f
            val shineX = badge.left - badge.width() * 0.30f + badge.width() * 1.65f * progress
            val shine = Path().apply {
                moveTo(shineX - badge.width() * 0.06f, badge.top)
                lineTo(shineX + badge.width() * 0.06f, badge.top)
                lineTo(shineX + badge.width() * 0.22f, badge.bottom)
                lineTo(shineX + badge.width() * 0.10f, badge.bottom)
                close()
            }
            canvas.save()
            canvas.clipPath(path)
            paint.color = Color.argb((86f * (1f - settle)).toInt(), 255, 255, 255)
            canvas.drawPath(shine, paint)
            canvas.restore()
        }

        val primaryHeight = if (card.badgeSecondary.isBlank()) badge.height() * 0.70f else badge.height() * 0.48f
        drawTextBlock(
            canvas,
            card.badgePrimary,
            RectF(
                badge.left + badge.width() * 0.08f,
                badge.centerY() - primaryHeight * 0.62f,
                badge.right - badge.width() * 0.08f,
                badge.centerY() + primaryHeight * 0.38f,
            ),
            Color.WHITE,
            true,
            badge.width() * 0.22f,
            badge.width() * 0.08f,
            2,
        )
        if (card.badgeSecondary.isNotBlank()) {
            drawTextBlock(
                canvas,
                card.badgeSecondary,
                RectF(
                    badge.left + badge.width() * 0.09f,
                    badge.centerY() + badge.height() * 0.08f,
                    badge.right - badge.width() * 0.09f,
                    badge.bottom - badge.height() * 0.12f,
                ),
                Color.WHITE,
                true,
                badge.width() * 0.105f,
                badge.width() * 0.055f,
                2,
            )
        }
    }

    private fun drawRelationshipsBadge(canvas: Canvas, card: CtsCard, badge: RectF, textAlpha: Float) {
        val path = Path().apply {
            moveTo(badge.centerX(), badge.top)
            lineTo(badge.left + badge.width() * 0.87f, badge.top + badge.height() * 0.18f)
            lineTo(badge.right, badge.top + badge.height() * 0.64f)
            lineTo(badge.left + badge.width() * 0.80f, badge.bottom)
            lineTo(badge.left + badge.width() * 0.20f, badge.bottom)
            lineTo(badge.left, badge.top + badge.height() * 0.64f)
            lineTo(badge.left + badge.width() * 0.13f, badge.top + badge.height() * 0.18f)
            close()
        }
        paint.shader = null
        paint.color = Color.rgb(224, 17, 27)
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, badge.width() * 0.006f)
        paint.color = Color.rgb(239, 194, 72)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        val textColor = Color.argb((255 * textAlpha.coerceIn(0f, 1f)).toInt(), 255, 255, 255)
        val number = relationshipNumber(card.badgePrimary)
        drawTextBlock(
            canvas,
            "1 in",
            RectF(badge.left + badge.width() * 0.10f, badge.top + badge.height() * 0.10f, badge.right - badge.width() * 0.10f, badge.top + badge.height() * 0.34f),
            textColor,
            true,
            badge.width() * 0.13f,
            badge.width() * 0.06f,
            1,
        )
        drawTextBlock(
            canvas,
            number,
            RectF(badge.left + badge.width() * 0.08f, badge.top + badge.height() * 0.27f, badge.right - badge.width() * 0.08f, badge.top + badge.height() * 0.72f),
            textColor,
            true,
            badge.width() * 0.30f,
            badge.width() * 0.12f,
            1,
        )
        drawTextBlock(
            canvas,
            "People",
            RectF(badge.left + badge.width() * 0.10f, badge.top + badge.height() * 0.70f, badge.right - badge.width() * 0.10f, badge.bottom - badge.height() * 0.08f),
            textColor,
            true,
            badge.width() * 0.12f,
            badge.width() * 0.055f,
            1,
        )
    }

    private fun relationshipNumber(value: String): String {
        val words = value.replace('\n', ' ').split(' ').filter { it.isNotBlank() }
        val inIndex = words.indexOfFirst { it.equals("in", ignoreCase = true) }
        return words.getOrNull(inIndex + 1)
            ?: words.firstOrNull { word -> word.any { it.isDigit() } }
            ?: value.ifBlank { "?" }
    }

    private fun frameRect(rect: NormalizedRect, cardWidth: Float): RectF = RectF(
        cardWidth * rect.x,
        height * rect.y,
        cardWidth * (rect.x + rect.width),
        height * (rect.y + rect.height),
    )

    private fun hexagon(rect: RectF): Path = Path().apply {
        moveTo(rect.centerX(), rect.top)
        lineTo(rect.right, rect.top + rect.height() * 0.22f)
        lineTo(rect.right, rect.top + rect.height() * 0.78f)
        lineTo(rect.centerX(), rect.bottom)
        lineTo(rect.left, rect.top + rect.height() * 0.78f)
        lineTo(rect.left, rect.top + rect.height() * 0.22f)
        close()
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, destination: RectF) {
        if (destination.width() <= 0f || destination.height() <= 0f) return
        val scale = max(destination.width() / bitmap.width, destination.height() / bitmap.height)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(
                destination.centerX() - bitmap.width * scale / 2f,
                destination.centerY() - bitmap.height * scale / 2f,
            )
        }
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    private fun drawTextBlock(
        canvas: Canvas,
        text: String,
        rect: RectF,
        color: Int,
        bold: Boolean,
        maximumSize: Float,
        minimumSize: Float,
        maxLines: Int,
    ) {
        if (text.isBlank() || rect.width() <= 2f || rect.height() <= 2f) return
        textPaint.color = color
        textPaint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        var size = maximumSize.coerceAtLeast(minimumSize)
        var layout: StaticLayout
        while (true) {
            textPaint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, max(1, rect.width().toInt()))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            if ((layout.height <= rect.height() && layout.lineCount <= maxLines) || size <= minimumSize) break
            size = max(minimumSize, size - 1f)
        }
        canvas.save()
        canvas.translate(rect.left, rect.top + max(0f, (rect.height() - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
    }

    private fun loadImage(source: String?): Bitmap? {
        val key = source?.trim().orEmpty()
        if (key.isBlank()) return null
        if (imageCache.containsKey(key)) return imageCache[key]
        val bitmap = runCatching {
            val stream = when {
                key.startsWith("http://", true) || key.startsWith("https://", true) -> {
                    URL(key).openConnection().apply {
                        connectTimeout = 15_000
                        readTimeout = 20_000
                        setRequestProperty("User-Agent", "CTS-Android-Exporter")
                    }.getInputStream()
                }
                key.startsWith("content://", true) || key.startsWith("file://", true) ->
                    context.contentResolver.openInputStream(Uri.parse(key))
                else -> FileInputStream(File(key))
            } ?: error("Could not open image")
            stream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        imageCache[key] = bitmap
        return bitmap
    }

    private companion object {
        val BADGE_FRAME = NormalizedRect(0.245f, 0.063f, 0.51f, 0.263f)
    }
}
