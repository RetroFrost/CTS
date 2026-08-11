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
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.github.retrofrost.cts.android.layout.CardContentLayout
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
    private val introRetriever: MediaMetadataRetriever? = project.introVideo.uri
        ?.takeIf { it.isNotBlank() }
        ?.let { source ->
            runCatching {
                MediaMetadataRetriever().apply {
                    val uri = Uri.parse(source)
                    if (uri.scheme.isNullOrBlank()) setDataSource(source) else setDataSource(context, uri)
                }
            }.getOrNull()
        }

    fun render(target: Bitmap, outputTimeSeconds: Float) {
        require(target.width == width && target.height == height)
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        if (TimelineEngine.customIntroVisible(project, outputTimeSeconds)) {
            drawCustomIntro(canvas, outputTimeSeconds)
            return
        }
        val cardWidth = width / 4f

        if (TimelineEngine.introCreditsVisible(project, outputTimeSeconds)) {
            ReferenceOverlayRenderer.drawIntroCredits(canvas, width, height, project.credits, paint)
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
            if (placement.badgeVisible) {
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
                true,
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
                project.credits,
                paint,
            )
        } else {
            ReferenceOverlayRenderer.drawOutro(canvas, width, height, cover, content, project.credits, paint)
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
        introRetriever?.release()
    }

    private fun drawCustomIntro(canvas: Canvas, outputTimeSeconds: Float) {
        val retriever = introRetriever ?: return
        val timeUs = (outputTimeSeconds.coerceAtLeast(0f) * 1_000_000f).toLong()
        val frame = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    width,
                    height,
                ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }.getOrNull() ?: return
        try {
            drawCenterCrop(canvas, frame, RectF(0f, 0f, width.toFloat(), height.toFloat()), 0.5f, 0.5f, 1f)
        } finally {
            if (!frame.isRecycled) frame.recycle()
        }
    }

    private fun drawCardBody(canvas: Canvas, card: CtsCard, cardWidth: Float, placement: CardPlacement) {
        val displayCard = card.withNormalizedText()
        val frames = CardContentLayout.frames(project.model, displayCard)
        val image = frameRect(frames.image, cardWidth)
        val title = frames.title?.let { frameRect(it, cardWidth) }
        val description = frames.description?.let { frameRect(it, cardWidth) }

        // Reference-model colours are owned by the model and are never replaced by app content.
        val topColor = if (project.model == VisualModel.Relationships) Color.rgb(0, 105, 211) else Color.rgb(19, 141, 219)
        val bottomColor = if (project.model == VisualModel.Relationships) Color.rgb(0, 88, 181) else Color.rgb(11, 116, 190)
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

        loadImage(displayCard.imageSubcard.source)?.let { bitmap ->
            val transform = displayCard.imageSubcard.transform.clamped()
            val destination = RectF(
                image.left + image.width() * transform.x,
                image.top + image.height() * transform.y,
                image.left + image.width() * (transform.x + transform.width),
                image.top + image.height() * (transform.y + transform.height),
            )
            canvas.save()
            canvas.clipRect(image)
            drawCenterCrop(
                canvas = canvas,
                bitmap = bitmap,
                destination = destination,
                focusX = displayCard.imageSubcard.cropFocusX,
                focusY = displayCard.imageSubcard.cropFocusY,
                zoom = displayCard.imageSubcard.cropZoom,
            )
            canvas.restore()
        }

        val padding = cardWidth * 0.035f
        title?.let {
            val alpha = if (project.model == VisualModel.Relationships) {
                max(placement.bodyReveal, placement.titleReveal).coerceIn(0f, 1f)
            } else 1f
            val layer = if (alpha < 0.999f) {
                canvas.saveLayerAlpha(it, (alpha * 255f).toInt())
            } else null
            paint.color = if (project.model == VisualModel.Relationships) {
                Color.rgb(245, 245, 243)
            } else Color.rgb(240, 240, 240)
            canvas.drawRect(it, paint)
            drawTextBlock(
                canvas = canvas,
                text = displayCard.title,
                rect = RectF(it.left + padding, it.top + 2f, it.right - padding, it.bottom - 2f),
                color = Color.rgb(16, 16, 16),
                bold = project.model != VisualModel.Relationships,
                maximumSize = height * (if (project.model == VisualModel.Relationships) 0.060f else 0.043f),
                minimumSize = height * (if (project.model == VisualModel.Relationships) 0.022f else 0.018f),
                maxLines = if (project.model == VisualModel.Relationships) 1 else 2,
            )
            layer?.let(canvas::restoreToCount)
        }
        description?.let {
            val alpha = if (project.model == VisualModel.Relationships) {
                max(placement.bodyReveal, placement.descriptionReveal).coerceIn(0f, 1f)
            } else 1f
            val layer = if (alpha < 0.999f) {
                canvas.saveLayerAlpha(it, (alpha * 255f).toInt())
            } else null
            paint.color = if (project.model == VisualModel.Relationships) {
                Color.rgb(47, 47, 47)
            } else Color.rgb(98, 95, 86)
            canvas.drawRect(it, paint)
            drawTextBlock(
                canvas = canvas,
                text = displayCard.description,
                rect = RectF(
                    it.left + padding,
                    it.top + 2f,
                    it.right - padding,
                    it.bottom - 2f,
                ),
                color = Color.WHITE,
                bold = project.model != VisualModel.Relationships,
                maximumSize = height * (if (project.model == VisualModel.Relationships) 0.036f else 0.027f),
                minimumSize = height * (if (project.model == VisualModel.Relationships) 0.018f else 0.014f),
                maxLines = if (project.model == VisualModel.Relationships) 4 else 3,
            )
            layer?.let(canvas::restoreToCount)
        }

        paint.color = Color.rgb(17, 16, 12)
        val bottomRule = frameRect(CardContentLayout.bottomRule(), cardWidth)
        canvas.drawRect(bottomRule, paint)

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
            CardContentLayout.relationshipsRule(displayCard)?.let { ruleFrame ->
                paint.color = Color.rgb(234, 127, 28)
                val rule = frameRect(ruleFrame, cardWidth)
                val ruleAlpha = max(placement.bodyReveal, placement.descriptionReveal).coerceIn(0f, 1f)
                val layer = if (ruleAlpha < 0.999f) {
                    canvas.saveLayerAlpha(rule, (ruleAlpha * 255f).toInt())
                } else null
                canvas.drawRect(rule, paint)
                layer?.let(canvas::restoreToCount)
            }
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
            card = card.withNormalizedText(),
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

    private fun drawCenterCrop(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        focusX: Float,
        focusY: Float,
        zoom: Float,
    ) {
        if (destination.width() <= 0f || destination.height() <= 0f) return
        val scale = max(destination.width() / bitmap.width, destination.height() / bitmap.height) *
            zoom.coerceIn(1f, 3f)
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val translationX = (destination.centerX() - bitmap.width * focusX.coerceIn(0f, 1f) * scale)
            .coerceIn(destination.right - scaledWidth, destination.left)
        val translationY = (destination.centerY() - bitmap.height * focusY.coerceIn(0f, 1f) * scale)
            .coerceIn(destination.bottom - scaledHeight, destination.top)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(translationX, translationY)
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
        val displayText = text.trim()
        if (displayText.isEmpty() || rect.width() <= 2f || rect.height() <= 2f) return
        textPaint.color = color
        textPaint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        var size = maximumSize.coerceAtLeast(minimumSize)
        var layout: StaticLayout
        while (true) {
            textPaint.textSize = size
            layout = StaticLayout.Builder.obtain(
                displayText,
                0,
                displayText.length,
                textPaint,
                max(1, rect.width().toInt()),
            )
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
