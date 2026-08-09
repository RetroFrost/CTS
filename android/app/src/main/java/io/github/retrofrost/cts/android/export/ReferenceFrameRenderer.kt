package io.github.retrofrost.cts.android.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
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
import io.github.retrofrost.cts.android.render.ReferenceBadgePainter
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
        ReferenceBadgePainter.draw(
            canvas = canvas,
            card = card,
            model = project.model,
            placement = placement,
            cardLeft = cardX,
            cardWidth = cardWidth,
            frameHeight = height.toFloat(),
        )
    }

    private fun frameRect(rect: NormalizedRect, cardWidth: Float): RectF = RectF(
        cardWidth * rect.x,
        height * rect.y,
        cardWidth * (rect.x + rect.width),
        height * (rect.y + rect.height),
    )

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
