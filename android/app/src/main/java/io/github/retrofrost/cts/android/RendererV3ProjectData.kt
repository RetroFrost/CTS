package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Project-data compositor for Renderer API v3 motion scenes.
 *
 * A Renderer v3 scene owns timing, transforms, clipping, badge geometry and layer
 * order. This object owns only the variable project payload inside those animated
 * slots. In particular it prevents source-measured renderer packages from becoming
 * pre-baked videos: card title/value/description/artwork always come from the active
 * StudioProject.
 */
object RendererV3ProjectData {
    private const val LEGACY_PUBERTY_ID = "watchdata-puberty-source-exact-1.4"
    private const val DATA_DRIVEN_PUBERTY_ID = "watchdata-puberty-data-driven-1.4.1"

    private val cardBodyKinds = setOf("openingCard", "card")
    private val badgeTextKinds = setOf("openingText", "badgeText", "laterText")
    private val badgeVisualKinds = setOf(
        "openingBadge", "badge", "laterBadge",
        "openingText", "badgeText", "laterText",
        "openingShine", "shineBroad", "shineCore", "shadow",
    )
    private val imageCache = ConcurrentHashMap<String, Bitmap>()

    fun enabled(scene: RendererV3Scene): Boolean =
        "project-card-data" in scene.features || scene.id == LEGACY_PUBERTY_ID || scene.id == DATA_DRIVEN_PUBERTY_ID

    fun enabled(spec: RendererSpec): Boolean =
        spec.engine == "scene-v3" && (
            "project-card-data" in spec.requiredFeatures ||
                spec.id == LEGACY_PUBERTY_ID ||
                spec.id == DATA_DRIVEN_PUBERTY_ID
            )

    fun isCardBody(obj: RendererV3Object): Boolean = obj.kind in cardBodyKinds
    fun isBadgeText(obj: RendererV3Object): Boolean = obj.kind in badgeTextKinds

    fun cardIndex(obj: RendererV3Object): Int? {
        val explicit = when {
            obj.raw.has("cardIndex") -> obj.raw.optInt("cardIndex", -1)
            obj.raw.has("dataIndex") -> obj.raw.optInt("dataIndex", -1)
            else -> -1
        }
        if (explicit >= 0) return explicit
        return obj.id.substringAfterLast('@', "").toIntOrNull()?.takeIf { it >= 0 }
    }

    /** Hide source slots that have no corresponding project card and all source-only ending media. */
    fun shouldRender(scene: RendererV3Scene, project: StudioProject, obj: RendererV3Object): Boolean {
        if (!enabled(scene)) return true
        if (obj.kind == "endingOverlay" || obj.kind == "fade") return false
        val index = cardIndex(obj)
        if (index != null && index !in project.cards.indices) return false
        if (index != null && obj.kind in badgeVisualKinds) {
            val card = project.cards[index]
            if (!project.showBadges) return false
            if (card.value.isBlank() && card.badgeHeader.isBlank()) return false
        }
        return true
    }

    /**
     * Automatic duration follows the project's last card, not the source video's
     * canonical card count. The scene's measured lifespan already includes the
     * exact scroll-out tail for each source card.
     */
    fun timelineFrameCount(scene: RendererV3Scene, project: StudioProject): Int {
        if (!enabled(scene)) return scene.timeline.frames.coerceAtLeast(1)
        val lastIndex = project.cards.lastIndex.coerceAtLeast(0)
        val lastCard = scene.objects.firstOrNull { it.kind == "card" && cardIndex(it) == lastIndex }
            ?: return scene.timeline.frames.coerceAtLeast(1)
        return (lastCard.lifespanEnd + 1).coerceIn(1, scene.timeline.frames)
    }

    fun drawCard(
        project: StudioProject,
        obj: RendererV3Object,
        resource: JSONObject,
        opacity: Float,
        canvas: Canvas,
    ) {
        val index = cardIndex(obj) ?: return
        val card = project.cards.getOrNull(index) ?: return
        val width = resource.optDouble("width", 470.0).toFloat().coerceAtLeast(1f)
        val height = resource.optDouble("height", 1080.0).toFloat().coerceAtLeast(1f)
        val topFieldHeight = resource.optDouble("topFieldHeight", 476.0).toFloat().coerceIn(0f, height)
        val configuredTitleHeight = resource.optDouble("titleHeight", 101.0).toFloat().coerceAtLeast(0f)
        val titleHeight = if (card.title.isBlank()) 0f else configuredTitleHeight.coerceAtMost(height - topFieldHeight)
        val contentTop = (topFieldHeight + titleHeight).coerceAtMost(height)

        canvas.save()
        if (opacity < 0.999f) canvas.saveLayerAlpha(null, (opacity * 255f).toInt().coerceIn(0, 255))
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            paint.color = color(resource, "topBackground", Color.rgb(29, 29, 29))
            paint.alpha = 255
            canvas.drawRect(0f, 0f, width, topFieldHeight, paint)

            // The measured WatchData card has red horizontal trails behind the badge.
            // They are style, not card content, so draw them procedurally.
            paint.color = color(resource, "trailColor", Color.rgb(211, 8, 9))
            val trailRows = floatArrayOf(166f, 192f, 218f, 244f, 270f, 296f)
            trailRows.forEachIndexed { row, y ->
                if (y < topFieldHeight) {
                    paint.alpha = when (row) {
                        0, 5 -> 170
                        1, 4 -> 205
                        else -> 235
                    }
                    canvas.drawRect(0f, y, width, min(y + 14f, topFieldHeight), paint)
                }
            }
            paint.alpha = 255

            if (titleHeight > 0f) {
                paint.color = color(resource, "titleBackground", Color.rgb(216, 214, 208))
                canvas.drawRect(0f, topFieldHeight, width, contentTop, paint)
                drawWrappedText(
                    canvas = canvas,
                    project = project,
                    text = card.title,
                    box = RectF(12f, topFieldHeight + 5f, width - 12f, contentTop - 5f),
                    textColor = color(resource, "titleText", Color.rgb(17, 17, 17)),
                    preferredSize = resource.optDouble("titleTextSize", 31.0).toFloat(),
                    minSize = 17f,
                    maxLines = 2,
                    bold = true,
                )
            }

            paint.color = color(resource, "descriptionBackground", Color.rgb(108, 103, 96))
            canvas.drawRect(0f, contentTop, width, height, paint)

            val hasDescription = card.description.isNotBlank()
            val hasArtwork = card.image.isNotBlank()
            val remaining = (height - contentTop).coerceAtLeast(0f)
            val descriptionHeight = when {
                !hasDescription -> 0f
                !hasArtwork -> remaining
                else -> min(165f, remaining * 0.34f)
            }

            if (hasDescription) {
                drawWrappedText(
                    canvas = canvas,
                    project = project,
                    text = card.description,
                    box = RectF(14f, contentTop + 8f, width - 14f, contentTop + descriptionHeight - 5f),
                    textColor = color(resource, "descriptionText", Color.rgb(230, 227, 221)),
                    preferredSize = resource.optDouble("descriptionTextSize", 23.0).toFloat(),
                    minSize = 13f,
                    maxLines = if (hasArtwork) 4 else 8,
                    bold = false,
                )
            }

            if (hasArtwork) {
                val artworkTop = (contentTop + descriptionHeight + 8f).coerceAtMost(height - 1f)
                val destination = RectF(16f, artworkTop, width - 16f, height - 16f)
                if (destination.width() > 1f && destination.height() > 1f) {
                    drawArtwork(canvas, card, destination)
                }
            }
        } finally {
            if (opacity < 0.999f) canvas.restore()
            canvas.restore()
        }
    }

    /** Draw current project badge text while preserving the scene's measured quad animation. */
    fun drawBadgeText(
        project: StudioProject,
        obj: RendererV3Object,
        resource: JSONObject,
        props: Map<String, Any?>,
        opacity: Float,
        canvas: Canvas,
    ) {
        val index = cardIndex(obj) ?: return
        val card = project.cards.getOrNull(index) ?: return
        if (card.value.isBlank() && card.badgeHeader.isBlank()) return

        val width = number(props["width"], resource.optDouble("width", 477.0)).toFloat().coerceAtLeast(1f)
        val height = number(props["height"], resource.optDouble("height", 420.0)).toFloat().coerceAtLeast(1f)
        val quad = points(props["geometry.quad"] ?: props["quad"])
        if (quad != null && quad.size == 4) {
            val rasterWidth = width.toInt().coerceAtLeast(2)
            val rasterHeight = height.toInt().coerceAtLeast(2)
            val bitmap = Bitmap.createBitmap(rasterWidth, rasterHeight, Bitmap.Config.ARGB_8888)
            try {
                drawBadgeContent(Canvas(bitmap), project, card, resource, 0f, 0f, width, height, 1f)
                val src = floatArrayOf(
                    0f, 0f,
                    rasterWidth.toFloat(), 0f,
                    rasterWidth.toFloat(), rasterHeight.toFloat(),
                    0f, rasterHeight.toFloat(),
                )
                val dst = FloatArray(8)
                quad.forEachIndexed { i, point ->
                    dst[i * 2] = point.first
                    dst[i * 2 + 1] = point.second
                }
                val matrix = Matrix()
                if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (opacity * 255f).toInt().coerceIn(0, 255)
                    }
                    canvas.drawBitmap(bitmap, matrix, paint)
                }
            } finally {
                bitmap.recycle()
            }
            return
        }

        val x = number(props["x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"], resource.optDouble("y", 0.0)).toFloat()
        drawBadgeContent(canvas, project, card, resource, x, y, width, height, opacity)
    }

    /** Replace the source-specific right-side outro with a project-neutral full-canvas fade. */
    fun drawEndFade(scene: RendererV3Scene, project: StudioProject, frame: Int, canvas: Canvas) {
        if (!enabled(scene)) return
        val total = timelineFrameCount(scene, project)
        if (frame !in 0 until total) return
        val template = scene.objects.firstOrNull { it.kind == "fade" } ?: return
        val fadeLength = (template.lifespanEnd - template.lifespanStart + 1).coerceAtLeast(1)
        val start = (total - fadeLength).coerceAtLeast(0)
        if (frame < start) return
        val sourceFrame = template.lifespanStart + (frame - start)
        val props = RendererV3Evaluator.properties(scene, template, sourceFrame)
        val opacity = number(props["opacity"], 0.0).toFloat().coerceIn(0f, 1f)
        if (opacity <= 0f) return
        val paint = Paint().apply {
            color = Color.BLACK
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        }
        canvas.drawRect(0f, 0f, scene.canvas.width.toFloat(), scene.canvas.height.toFloat(), paint)
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
        val baseScale = min(destination.width() / sourceWidth, destination.height() / sourceHeight)
        val scale = baseScale * card.imageScale.coerceIn(0.05, 12.0).toFloat()
        val drawnWidth = sourceWidth * scale
        val drawnHeight = sourceHeight * scale
        val cx = destination.centerX() + card.imageX.toFloat()
        val cy = destination.centerY() + card.imageY.toFloat()
        val target = RectF(cx - drawnWidth / 2f, cy - drawnHeight / 2f, cx + drawnWidth / 2f, cy + drawnHeight / 2f)

        canvas.save()
        canvas.clipRect(destination)
        if (card.imageRotation != 0.0) canvas.rotate(card.imageRotation.toFloat(), cx, cy)
        canvas.drawBitmap(bitmap, source, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }

    private fun loadImage(path: String): Bitmap? {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://")) return null
        imageCache[path]?.takeIf { !it.isRecycled }?.let { return it }
        val file = File(path)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()?.also { imageCache[path] = it }
    }

    private fun drawBadgeContent(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        resource: JSONObject,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        opacity: Float,
    ) {
        val header = card.badgeHeader.trim()
        val value = card.value.trim()
        val parts = value.split(Regex("\\s+"), limit = 2).filter { it.isNotBlank() }
        val primary = parts.firstOrNull().orEmpty()
        val unit = parts.getOrNull(1).orEmpty()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color(resource, "color", Color.WHITE)
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
            typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", Typeface.BOLD), Typeface.BOLD)
        }
        val cx = x + width / 2f
        val valueSize = resource.optDouble("valueSize", 58.0).toFloat()
        val unitSize = resource.optDouble("unitSize", 28.0).toFloat()
        val headerSize = resource.optDouble("headerSize", 24.0).toFloat()
        val maxWidth = width * 0.54f

        when {
            header.isNotBlank() && primary.isNotBlank() && unit.isNotBlank() -> {
                drawFitLine(canvas, paint, header, cx, y + height * 0.41f, headerSize, maxWidth)
                drawFitLine(canvas, paint, primary, cx, y + height * 0.55f, valueSize, maxWidth)
                drawFitLine(canvas, paint, unit, cx, y + height * 0.68f, unitSize, maxWidth)
            }
            header.isNotBlank() && primary.isNotBlank() -> {
                drawFitLine(canvas, paint, header, cx, y + height * 0.46f, headerSize, maxWidth)
                drawFitLine(canvas, paint, value, cx, y + height * 0.61f, valueSize * 0.88f, maxWidth)
            }
            header.isNotBlank() -> drawFitLine(canvas, paint, header, cx, y + height * 0.56f, headerSize, maxWidth)
            unit.isNotBlank() -> {
                drawFitLine(canvas, paint, primary, cx, y + height * 0.53f, valueSize, maxWidth)
                drawFitLine(canvas, paint, unit, cx, y + height * 0.66f, unitSize, maxWidth)
            }
            else -> drawFitLine(canvas, paint, primary, cx, y + height * 0.58f, valueSize, maxWidth)
        }
    }

    private fun drawFitLine(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        centerY: Float,
        preferredSize: Float,
        maxWidth: Float,
    ) {
        if (text.isBlank()) return
        var size = preferredSize.coerceAtLeast(10f)
        paint.textSize = size
        while (paint.measureText(text) > maxWidth && size > 10f) {
            size -= 1f
            paint.textSize = size
        }
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun drawWrappedText(
        canvas: Canvas,
        project: StudioProject,
        text: String,
        box: RectF,
        textColor: Int,
        preferredSize: Float,
        minSize: Float,
        maxLines: Int,
        bold: Boolean,
    ) {
        if (text.isBlank() || box.width() <= 1f || box.height() <= 1f) return
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
            typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", style), style)
        }
        var size = preferredSize.coerceAtLeast(minSize)
        var lines: List<String>
        while (true) {
            paint.textSize = size
            lines = wrap(text, box.width(), paint, maxLines)
            val lineHeight = size * 1.08f
            if ((lines.size * lineHeight <= box.height() && lines.all { paint.measureText(it) <= box.width() }) || size <= minSize) break
            size = (size - 1f).coerceAtLeast(minSize)
        }
        paint.textSize = size
        val lineHeight = size * 1.08f
        val total = lineHeight * lines.size
        var baseline = box.centerY() - total / 2f - paint.ascent()
        lines.forEach { line ->
            canvas.drawText(line, box.centerX(), baseline, paint)
            baseline += lineHeight
        }
    }

    private fun wrap(text: String, width: Float, paint: Paint, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
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

    private fun points(value: Any?): List<Pair<Float, Float>>? {
        val list = value as? List<*> ?: return null
        return list.mapNotNull { point ->
            val pair = point as? List<*> ?: return@mapNotNull null
            if (pair.size < 2) return@mapNotNull null
            val px = (pair[0] as? Number)?.toFloat() ?: return@mapNotNull null
            val py = (pair[1] as? Number)?.toFloat() ?: return@mapNotNull null
            px to py
        }.takeIf { it.isNotEmpty() }
    }

    private fun number(value: Any?, fallback: Double): Double =
        (value as? Number)?.toDouble() ?: value?.toString()?.toDoubleOrNull() ?: fallback

    private fun color(resource: JSONObject, key: String, fallback: Int): Int = runCatching {
        val value = resource.optString(key)
        if (value.isBlank()) fallback else Color.parseColor(value)
    }.getOrDefault(fallback)
}
