package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Opt-in precision interpreter for relationships-exact renderer bundles.
 *
 * It is enabled by the renderer tag `relationships.exact.v2=true`. Existing
 * relationships bundles continue to use [RelationshipsFrameRenderer]. The v2
 * path deliberately moves source-specific values out of Kotlin and into the
 * renderer bundle: exact card bands, divider, typefaces, badge polygon,
 * gradient/stroke/shadow/shine and frame-addressed motion can all be declared.
 */
class RelationshipsPrecisionFrameRenderer {
    private val imageCache = object : LinkedHashMap<String, Bitmap>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > 12
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }
    private val typefaceCache = LinkedHashMap<String, Typeface>(8, 0.75f, true)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var cachedConfigKey: String? = null
    private var cachedConfig: ExactConfig? = null

    private fun exactConfig(spec: RendererSpec): ExactConfig {
        val key = "${spec.id}:${spec.tags.hashCode()}"
        val existing = cachedConfig
        if (existing != null && cachedConfigKey == key) return existing
        return ExactConfig(spec).also {
            cachedConfigKey = key
            cachedConfig = it
        }
    }

    @Synchronized
    fun render(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): Bitmap {
        val reference = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val spec = RendererRuntime.active
        val cfg = exactConfig(spec)
        val ledger = RenderPassLedger()
        drawReference(Canvas(reference), project, frameIndex.coerceAtLeast(0), spec, cfg, ledger)
        if (outputWidth == 1920 && outputHeight == 1080) return reference
        val output = Bitmap.createBitmap(outputWidth.coerceAtLeast(2), outputHeight.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        paint.alpha = 255
        paint.shader = null
        Canvas(output).drawBitmap(reference, Rect(0, 0, 1920, 1080), Rect(0, 0, output.width, output.height), paint)
        reference.recycle()
        return output
    }

    @Synchronized
    fun renderRgba(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): ByteArray {
        val bitmap = render(project, frameIndex, outputWidth, outputHeight)
        return try {
            ByteArray(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawReference(
        canvas: Canvas,
        project: StudioProject,
        frame: Int,
        spec: RendererSpec,
        cfg: ExactConfig,
        ledger: RenderPassLedger,
    ) {
        canvas.drawColor(spec.backgroundColor)
        ledger.once("footer.waveform") { drawFooterWaveform(canvas, frame, cfg) }
        if (project.cards.isEmpty()) {
            ledger.once("intro") { drawIntroLogo(canvas, frame, spec, cfg) }
            return
        }
        val contentEnd = RelationshipsTimeline.contentEndFrame(project, spec)
        when {
            frame < spec.openingStarts.firstOrNull().orZero() -> {
                ledger.once("intro") { drawIntroLogo(canvas, frame, spec, cfg) }
            }
            frame < contentEnd -> {
                if (frame < cfg.int("intro.overlayUntilFrame", spec.openingStarts.firstOrNull().orZero())) {
                    ledger.once("intro") { drawIntroLogo(canvas, frame, spec, cfg) }
                }
                ledger.once("content") { drawContent(canvas, project, frame, spec, cfg, ledger) }
            }
            else -> ledger.once("outro") { drawOutro(canvas, project, frame, contentEnd, spec, cfg, ledger) }
        }
    }

    private fun drawFooterWaveform(canvas: Canvas, frame: Int, cfg: ExactConfig) {
        if (!cfg.bool("footer.waveform.enabled", false)) return
        val data = cfg.gzipBase64("footer.waveform.data.gzipBase64") ?: return
        val barCount = cfg.int("footer.waveform.barCount", 87).coerceIn(1, 512)
        val bytesPerBar = cfg.int("footer.waveform.bytesPerBar", 2).coerceIn(2, 3)
        val stride = barCount * bytesPerBar
        if (data.size < stride) return
        val frameCount = data.size / stride
        if (frameCount <= 0) return
        val sourceFrame = (frame + cfg.int("footer.waveform.frameOffset", 0)).coerceIn(0, frameCount - 1)
        val offset = sourceFrame * stride
        val x0 = cfg.float("footer.waveform.x0", 6f)
        val step = cfg.float("footer.waveform.step", 22f)
        val width = cfg.float("footer.waveform.width", 1f).coerceAtLeast(0.25f)
        val baseline = cfg.float("footer.waveform.baselineY", 1068f)
        val baseColor = cfg.color("footer.waveform.color", Color.rgb(76, 76, 76))
        val globalAlpha = cfg.float("footer.waveform.alpha", 1f).coerceIn(0f, 1f)

        paint.resetForShape()
        paint.isAntiAlias = cfg.bool("footer.waveform.antialias", false)
        repeat(barCount) { index ->
            val p = offset + index * bytesPerBar
            val up = data[p].toInt() and 0xff
            val down = data[p + 1].toInt() and 0xff
            val encodedAlpha = if (bytesPerBar >= 3) (data[p + 2].toInt() and 0xff) / 255f else 1f
            if (up == 0 && down == 0) return@repeat
            paint.color = withAlpha(baseColor, globalAlpha * encodedAlpha)
            val x = x0 + index * step
            canvas.drawRect(x, baseline - up, x + width, baseline + down + 1f, paint)
        }
        paint.isAntiAlias = true
    }

    private fun drawIntroLogo(canvas: Canvas, frame: Int, spec: RendererSpec, cfg: ExactConfig) {
        if (!cfg.bool("intro.enabled", false)) return
        val fadeIn = smooth((frame / cfg.float("intro.fadeInFrames", 36f)).coerceIn(0f, 1f))
        val legacyScale = when {
            frame < 90 -> 1.42f - 0.46f * smooth(frame / 90f)
            else -> 0.96f + 0.04f * smooth(((180 - frame).coerceAtLeast(0)) / 90f)
        }
        val legacyAlpha = if (frame > 340) ((384 - frame) / 44f).coerceIn(0f, 1f) else 1f
        val scale = spec.track("relationships.intro.logo.scale", frame) ?: legacyScale
        val alpha = (spec.track("relationships.intro.logo.alpha", frame) ?: legacyAlpha).coerceIn(0f, 1f)
        val cx = cfg.float("intro.logo.cx", 960f)
        val cy = cfg.float("intro.logo.cy", 470f)
        val rx = cfg.float("intro.logo.rx", 250f)
        val ry = cfg.float("intro.logo.ry", 118f)
        val gap = cfg.float("intro.logo.gap", 5f)

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        val layerCount = cfg.int("intro.logo.layerCount", 0).coerceIn(0, 12)
        if (layerCount > 0) {
            repeat(layerCount) { layer ->
                paint.resetForShape()
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = cfg.float("intro.logo.layer.$layer.strokeWidth", cfg.float("intro.logo.strokeWidth", 9f))
                paint.alpha = (255 * fadeIn * alpha * cfg.float("intro.logo.layer.$layer.alpha", 1f).coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
                val shadowRadius = cfg.float("intro.logo.layer.$layer.shadowRadius", 0f)
                val shadowColor = cfg.color("intro.logo.layer.$layer.shadowColor", Color.TRANSPARENT)
                if (shadowRadius > 0f && Color.alpha(shadowColor) > 0) {
                    paint.setShadowLayer(
                        shadowRadius,
                        cfg.float("intro.logo.layer.$layer.shadowDx", 0f),
                        cfg.float("intro.logo.layer.$layer.shadowDy", 0f),
                        shadowColor,
                    )
                }
                paint.color = cfg.color("intro.logo.layer.$layer.leftColor", cfg.color("intro.logo.leftColor", Color.rgb(216, 235, 42)))
                canvas.drawArc(RectF(cx - rx, cy - ry, cx - gap, cy + ry), cfg.float("intro.logo.leftStart", 42f), cfg.float("intro.logo.leftSweep", 276f), false, paint)
                paint.color = cfg.color("intro.logo.layer.$layer.rightColor", cfg.color("intro.logo.rightColor", Color.rgb(238, 111, 139)))
                canvas.drawArc(RectF(cx + gap, cy - ry, cx + rx, cy + ry), cfg.float("intro.logo.rightStart", 222f), cfg.float("intro.logo.rightSweep", 276f), false, paint)
                paint.clearShadowLayer()
            }
        } else {
            paint.resetForShape()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = cfg.float("intro.logo.strokeWidth", 9f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.alpha = (255 * fadeIn * alpha).roundToInt().coerceIn(0, 255)
            paint.color = cfg.color("intro.logo.leftColor", Color.rgb(216, 235, 42))
            canvas.drawArc(RectF(cx - rx, cy - ry, cx - gap, cy + ry), cfg.float("intro.logo.leftStart", 42f), cfg.float("intro.logo.leftSweep", 276f), false, paint)
            paint.color = cfg.color("intro.logo.rightColor", Color.rgb(238, 111, 139))
            canvas.drawArc(RectF(cx + gap, cy - ry, cx + rx, cy + ry), cfg.float("intro.logo.rightStart", 222f), cfg.float("intro.logo.rightSweep", 276f), false, paint)
        }

        paint.resetForShape()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = cfg.float("intro.logo.crossStrokeWidth", 3f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.alpha = (255 * fadeIn * alpha).roundToInt().coerceIn(0, 255)
        paint.color = cfg.color("intro.logo.crossColor", Color.rgb(58, 58, 58))
        val crossX = cfg.float("intro.logo.crossX", 168f)
        val crossY = cfg.float("intro.logo.crossY", 86f)
        val cross = Path().apply {
            moveTo(cx - crossX, cy - crossY); lineTo(cx + crossX, cy + crossY)
            moveTo(cx + crossX, cy - crossY); lineTo(cx - crossX, cy + crossY)
        }
        canvas.drawPath(cross, paint)
        canvas.restore()
        paint.resetForShape()

        val full = cfg.string("intro.text", "Infinite\nComparison").replace("\\n", "\n")
        val chars = spec.track("relationships.intro.text.chars", frame)?.roundToInt()
            ?: (((frame - cfg.int("intro.text.startFrame", 170)) / cfg.float("intro.text.framesPerChar", 2.4f)).toInt())
        if (chars > 0) {
            val visible = full.take(chars.coerceIn(0, full.length))
            textPaint.color = cfg.color("intro.text.color", Color.WHITE)
            textPaint.alpha = (255 * alpha).roundToInt().coerceIn(0, 255)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = typeface(spec, cfg, "intro", "sans-serif-light", Typeface.NORMAL)
            textPaint.letterSpacing = cfg.float("font.intro.letterSpacing", 0f)
            textPaint.textSize = cfg.float("intro.text.size", 34f)
            val y = cfg.float("intro.text.y", 640f)
            val lineGap = cfg.float("intro.text.lineGap", 38f)
            visible.split('\n').forEachIndexed { index, line -> canvas.drawText(line, cx, y + index * lineGap, textPaint) }
            textPaint.alpha = 255
            textPaint.letterSpacing = 0f
        }
    }

    private fun drawContent(
        canvas: Canvas,
        project: StudioProject,
        frame: Int,
        spec: RendererSpec,
        cfg: ExactConfig,
        ledger: RenderPassLedger,
    ) {
        val positions = linkedMapOf<Int, Float>()
        if (frame < spec.continuousStartFrame) {
            val starts = spec.openingStarts
            for (index in 0 until min(4, project.cards.size)) {
                val start = starts.getOrElse(index) { starts.lastOrNull().orZero() + index * 140 }
                if (frame >= start) {
                    positions[index] = spec.trackWindowed("card.$index.x", frame) ?: index * spec.slotPitch
                }
            }
        } else {
            val scroll = exactScroll(spec, frame) ?: ((frame - spec.continuousStartFrame) * 2f)
            project.cards.indices.forEach { index ->
                val baseX = index * spec.slotPitch - scroll
                val x = spec.trackWindowed("card.$index.x", frame) ?: baseX
                if (x > -spec.slotPitch * 2f && x < 1920f + spec.slotPitch * 2f) positions[index] = x
            }
        }

        positions.forEach { (index, x) ->
            ledger.once("card.$index.body") {
                val y = spec.trackWindowed("card.$index.y", frame) ?: 0f
                val entry = RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec)
                val local = frame - entry
                val uniform = spec.trackWindowed("card.$index.body.scale", frame)
                    ?: spec.track("relationships.card.body.scale", local)
                    ?: 1f
                val scaleX = spec.trackWindowed("card.$index.body.scaleX", frame)
                    ?: spec.track("relationships.card.body.scaleX", local)
                    ?: uniform
                val scaleY = spec.trackWindowed("card.$index.body.scaleY", frame)
                    ?: spec.track("relationships.card.body.scaleY", local)
                    ?: uniform
                val pivotX = x + cfg.float("card.body.pivotX", spec.bodyInset + spec.bodyWidth / 2f)
                val pivotY = cfg.float("card.body.pivotY", 540f)
                canvas.save()
                canvas.translate(0f, y)
                canvas.scale(scaleX, scaleY, pivotX, pivotY)
                drawCardBody(canvas, project, project.cards[index], x, spec, cfg, frame, index)
                canvas.restore()
            }
        }
        if (project.creditsEnabled && frame in spec.openingStarts.firstOrNull().orZero() until spec.continuousStartFrame) {
            ledger.once("disclaimer") { drawDisclaimer(canvas, frame, spec, cfg) }
        }
        positions.forEach { (index, x) ->
            ledger.once("card.$index.badge") { drawBadge(canvas, project, index, x, frame, spec, cfg) }
        }
        positions.forEach { (index, x) ->
            if (project.cards[index].imageLayer.equals("front", true)) {
                ledger.once("card.$index.artwork.front") {
                    val y = spec.trackWindowed("card.$index.y", frame) ?: 0f
                    val entry = RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec)
                    val local = frame - entry
                    val uniform = spec.trackWindowed("card.$index.body.scale", frame)
                        ?: spec.track("relationships.card.body.scale", local)
                        ?: 1f
                    val scaleX = spec.trackWindowed("card.$index.body.scaleX", frame)
                        ?: spec.track("relationships.card.body.scaleX", local)
                        ?: uniform
                    val scaleY = spec.trackWindowed("card.$index.body.scaleY", frame)
                        ?: spec.track("relationships.card.body.scaleY", local)
                        ?: uniform
                    val pivotX = x + cfg.float("card.body.pivotX", spec.bodyInset + spec.bodyWidth / 2f)
                    val pivotY = cfg.float("card.body.pivotY", 540f)
                    canvas.save()
                    canvas.translate(0f, y)
                    canvas.scale(scaleX, scaleY, pivotX, pivotY)
                    drawFrontArtwork(canvas, project.cards[index], x, spec, cfg)
                    canvas.restore()
                }
            }
        }
    }

    private fun exactScroll(spec: RendererSpec, frame: Int): Float? {
        if (frame < spec.continuousStartFrame) return null
        val segment = (frame - spec.continuousStartFrame) / 4096
        return spec.track("relationships.scroll.$segment", frame)
    }

    private fun drawCardBody(canvas: Canvas, project: StudioProject, card: StudioCard, slotX: Float, spec: RendererSpec, cfg: ExactConfig, frame: Int, index: Int) {
        val left = slotX + spec.bodyInset
        val right = left + spec.bodyWidth
        val absoluteBands = cfg.bool("card.absoluteBands", true)
        val hasTitle = card.title.isNotBlank()
        val hasDescription = card.description.isNotBlank()

        val imageBottom: Float
        val titleTop: Float
        val titleBottom: Float
        val descriptionTop: Float
        if (absoluteBands) {
            imageBottom = spec.imageHeight
            titleTop = imageBottom
            titleBottom = titleTop + if (hasTitle) spec.titleHeight else 0f
            descriptionTop = if (hasDescription) spec.descriptionTop else titleBottom
        } else {
            val descriptionHeight = if (hasDescription) cfg.float("card.legacyDescriptionHeight", 115f) else 0f
            val titleHeight = if (hasTitle) spec.titleHeight else 0f
            imageBottom = 1080f - descriptionHeight - titleHeight
            titleTop = imageBottom
            titleBottom = titleTop + titleHeight
            descriptionTop = titleBottom
        }

        val imageRect = RectF(left, 0f, right, imageBottom)
        val imageTopRadius = cfg.float("card.image.topRadius", 0f).coerceAtLeast(0f)
        val imagePath = panelPath(imageRect, imageTopRadius, imageTopRadius, 0f, 0f)
        paint.resetForShape()
        paint.color = cfg.color("card.imageFallbackColor", Color.rgb(30, 30, 30))
        if (imageTopRadius > 0f) canvas.drawPath(imagePath, paint) else canvas.drawRect(imageRect, paint)

        val entry = RelationshipsTimeline.cardEntryFrame(projectSize = Int.MAX_VALUE, index = index, spec = spec)
        val local = frame - entry
        val legacyReveal = if (index < 4) ((local - 52f) / 42f).coerceIn(0f, 1f) else 1f
        val reveal = (spec.trackWindowed("card.$index.body.reveal", frame)
            ?: spec.track("relationships.card.reveal", local)
            ?: legacyReveal).coerceIn(0f, 1f)
        if (!card.imageLayer.equals("front", true) && reveal > 0f) {
            val revealBottom = imageBottom * reveal
            canvas.save()
            if (imageTopRadius > 0f) canvas.clipPath(imagePath)
            canvas.clipRect(left, 0f, right, revealBottom)
            drawArtwork(canvas, card, imageRect)
            canvas.restore()
        }

        if (hasTitle) {
            val titleRect = RectF(left, titleTop, right, titleBottom)
            val titleTopRadius = cfg.float("card.title.topRadius", 0f).coerceAtLeast(0f)
            paint.resetForShape(); paint.color = spec.titleBackgroundColor
            if (titleTopRadius > 0f) {
                canvas.drawPath(panelPath(titleRect, titleTopRadius, titleTopRadius, 0f, 0f), paint)
            } else {
                canvas.drawRect(titleRect, paint)
            }
            val titleAlpha = (spec.trackWindowed("card.$index.title.alpha", frame)
                ?: spec.track("relationships.card.title.alpha", local)
                ?: 1f).coerceIn(0f, 1f)
            val titleChars = spec.trackWindowed("card.$index.title.chars", frame)?.roundToInt()
                ?: spec.track("relationships.card.title.chars", local)?.roundToInt()
            val titleText = if (titleChars == null) card.title else card.title.take(titleChars.coerceIn(0, card.title.length))
            if (titleText.isNotBlank() && titleAlpha > 0f) {
                drawFitted(
                    canvas, titleText,
                    RectF(left + cfg.float("card.title.padX", 10f), titleTop + cfg.float("card.title.padTop", 1f), right - cfg.float("card.title.padX", 10f), titleBottom - cfg.float("card.title.padBottom", 1f)),
                    spec.titleTextColor, spec.titleTextSize,
                    ProjectFontResolver.resolve(project, typeface(spec, cfg, "title", "sans-serif", Typeface.BOLD), Typeface.BOLD),
                    cfg.int("card.title.maxLines", 1), cfg.float("card.title.lineHeight", 0.92f), titleAlpha, cfg.float("font.title.letterSpacing", 0f),
                )
            }
        }

        if (hasDescription && descriptionTop > titleBottom) {
            paint.resetForShape(); paint.color = cfg.color("card.divider.color", spec.descriptionBackgroundColor)
            canvas.drawRect(left, titleBottom, right, descriptionTop, paint)
        }
        if (hasDescription) {
            val descriptionRect = RectF(left, descriptionTop, right, 1080f)
            val descriptionBottomRadius = cfg.float("card.description.bottomRadius", 0f).coerceAtLeast(0f)
            paint.resetForShape(); paint.color = spec.descriptionBackgroundColor
            if (descriptionBottomRadius > 0f) {
                canvas.drawPath(panelPath(descriptionRect, 0f, 0f, descriptionBottomRadius, descriptionBottomRadius), paint)
            } else {
                canvas.drawRect(descriptionRect, paint)
            }
            val descriptionAlpha = (spec.trackWindowed("card.$index.description.alpha", frame)
                ?: spec.track("relationships.card.description.alpha", local)
                ?: 1f).coerceIn(0f, 1f)
            val descriptionChars = spec.trackWindowed("card.$index.description.chars", frame)?.roundToInt()
                ?: spec.track("relationships.card.description.chars", local)?.roundToInt()
            val descriptionText = if (descriptionChars == null) card.description else card.description.take(descriptionChars.coerceIn(0, card.description.length))
            if (descriptionText.isNotBlank() && descriptionAlpha > 0f) {
                drawFitted(
                    canvas, descriptionText,
                    RectF(left + cfg.float("card.description.padX", 11f), descriptionTop + cfg.float("card.description.padTop", 4f), right - cfg.float("card.description.padX", 11f), 1080f - cfg.float("card.description.padBottom", 4f)),
                    spec.descriptionTextColor, spec.descriptionTextSize,
                    ProjectFontResolver.resolve(project, typeface(spec, cfg, "description", "sans-serif", Typeface.NORMAL), Typeface.NORMAL),
                    cfg.int("card.description.maxLines", 4), cfg.float("card.description.lineHeight", 0.92f), descriptionAlpha, cfg.float("font.description.letterSpacing", 0f),
                )
            }
        }
    }

    private fun panelPath(rect: RectF, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float): Path =
        Path().apply {
            addRoundRect(
                rect,
                floatArrayOf(
                    topLeft, topLeft,
                    topRight, topRight,
                    bottomRight, bottomRight,
                    bottomLeft, bottomLeft,
                ),
                Path.Direction.CW,
            )
        }

    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec, cfg: ExactConfig) {
        val absoluteBands = cfg.bool("card.absoluteBands", true)
        val bottom = if (absoluteBands) spec.imageHeight else {
            val desc = if (card.description.isBlank()) 0f else cfg.float("card.legacyDescriptionHeight", 115f)
            val title = if (card.title.isBlank()) 0f else spec.titleHeight
            1080f - desc - title
        }
        val destination = RectF(slotX + spec.bodyInset, 0f, slotX + spec.bodyInset + spec.bodyWidth, bottom)
        val topRadius = cfg.float("card.image.topRadius", 0f).coerceAtLeast(0f)
        canvas.save()
        if (topRadius > 0f) canvas.clipPath(panelPath(destination, topRadius, topRadius, 0f, 0f))
        drawArtwork(canvas, card, destination)
        canvas.restore()
    }

    private fun drawArtwork(canvas: Canvas, card: StudioCard, destination: RectF) {
        val bitmap = loadImage(card.image) ?: return
        val left = (bitmap.width * card.imageCropLeft.coerceIn(0.0, 0.95)).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * card.imageCropTop.coerceIn(0.0, 0.95)).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * (1.0 - card.imageCropRight.coerceIn(0.0, 0.95))).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * (1.0 - card.imageCropBottom.coerceIn(0.0, 0.95))).roundToInt().coerceIn(top + 1, bitmap.height)
        val source = Rect(left, top, right, bottom)
        val base = max(destination.width() / source.width().coerceAtLeast(1), destination.height() / source.height().coerceAtLeast(1))
        val scale = base * card.imageScale.coerceIn(0.05, 12.0).toFloat()
        val w = source.width() * scale
        val h = source.height() * scale
        val cx = destination.centerX() + card.imageX.toFloat()
        val cy = destination.centerY() + card.imageY.toFloat()
        val target = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.save(); canvas.clipRect(destination)
        if (card.imageRotation != 0.0) canvas.rotate(card.imageRotation.toFloat(), cx, cy)
        paint.resetForShape(); paint.alpha = 255
        canvas.drawBitmap(bitmap, source, target, paint)
        canvas.restore()
    }

    private fun loadImage(path: String): Bitmap? {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://")) return null
        imageCache[path]?.let { if (!it.isRecycled) return it }
        val file = File(path)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()?.also { imageCache[path] = it }
    }

    private fun drawBadge(canvas: Canvas, project: StudioProject, index: Int, cardX: Float, frame: Int, spec: RendererSpec, cfg: ExactConfig) {
        if (!project.showBadges) return
        val card = project.cards[index]
        if (card.value.isBlank() && card.badgeHeader.isBlank()) return
        val entry = RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec)
        val local = frame - entry
        if (local < 0) return

        val scale = (spec.trackWindowed("card.$index.badge.scale", frame)
            ?: spec.track("relationships.badge.scale", local)
            ?: if (local < 45) smooth(local / 45f) else 1f).coerceIn(0f, cfg.float("badge.maxScale", 2f))
        val yOffset = spec.trackWindowed("card.$index.badge.y", frame) ?: spec.track("relationships.badge.y", local) ?: 0f
        val xOffset = spec.trackWindowed("card.$index.badge.x", frame) ?: 0f
        val cx = spec.badgeCenterX
        val cy = spec.badgeCenterY

        canvas.save()
        canvas.translate(cardX + xOffset, yOffset)
        canvas.scale(scale * spec.badgeScale, scale * spec.badgeScale, cx, cy)
        val path = badgePath(cfg, cx, cy)

        val shadowColor = cfg.color("badge.shadow.color", Color.TRANSPARENT)
        val shadowRadius = cfg.float("badge.shadow.radius", 0f).coerceAtLeast(0f)
        val shadowDx = cfg.float("badge.shadow.dx", 0f)
        val shadowDy = cfg.float("badge.shadow.dy", 0f)
        if (Color.alpha(shadowColor) > 0 && (shadowRadius > 0f || shadowDx != 0f || shadowDy != 0f)) {
            drawBadgeShadow(canvas, path, shadowColor, shadowRadius, shadowDx, shadowDy)
        }

        val gradientTop = cfg.color("badge.gradient.top", spec.badgeColor)
        val gradientBottom = cfg.color("badge.gradient.bottom", spec.badgeColor)
        paint.resetForShape()
        paint.style = Paint.Style.FILL
        paint.shader = if (gradientTop != gradientBottom || cfg.has("badge.gradient.top") || cfg.has("badge.gradient.bottom")) {
            LinearGradient(
                cx,
                cy + cfg.float("badge.gradient.startY", -cfg.float("badge.radiusY", 177f)),
                cx,
                cy + cfg.float("badge.gradient.endY", cfg.float("badge.radiusY", 177f)),
                gradientTop,
                gradientBottom,
                Shader.TileMode.CLAMP,
            )
        } else null
        paint.color = spec.badgeColor
        canvas.drawPath(path, paint)
        paint.shader = null

        val strokeCount = cfg.int("badge.stroke.count", 1).coerceIn(0, 8)
        repeat(strokeCount) { layer ->
            val width = if (strokeCount == 1) cfg.float("badge.stroke.width", 4f) else cfg.float("badge.stroke.$layer.width", 4f)
            if (width > 0f) {
                paint.resetForShape(); paint.style = Paint.Style.STROKE; paint.strokeWidth = width
                paint.color = if (strokeCount == 1) cfg.color("badge.stroke.color", spec.badgeDarkColor) else cfg.color("badge.stroke.$layer.color", spec.badgeDarkColor)
                canvas.drawPath(path, paint)
            }
        }

        drawBadgeShine(canvas, path, card, index, frame, local, spec, cfg)
        val textAlpha = (spec.trackWindowed("card.$index.badge.text.alpha", frame)
            ?: spec.track("relationships.badge.text.alpha", local)
            ?: 1f).coerceIn(0f, 1f)
        if (textAlpha > 0f) drawBadgeText(canvas, project, card, spec, cfg, textAlpha)
        canvas.restore()
        paint.resetForShape()
    }

    private fun badgePath(cfg: ExactConfig, cx: Float, cy: Float): Path {
        val declared = cfg.floatList("badge.points")
        if (declared.size >= 6 && declared.size % 2 == 0) {
            return Path().apply {
                declared.chunked(2).forEachIndexed { i, p ->
                    val x = cx + p[0]
                    val y = cy + p[1]
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
        val rx = cfg.float("badge.radiusX", 184f)
        val ry = cfg.float("badge.radiusY", 177f)
        val cornerX = cfg.float("badge.cornerX", 92f)
        val upperY = cfg.float("badge.upperCornerY", 88f)
        val lowerY = cfg.float("badge.lowerCornerY", 86f)
        val pts = arrayOf(
            cx - cornerX to cy - ry,
            cx + cornerX to cy - ry,
            cx + rx to cy - upperY,
            cx + rx to cy + lowerY,
            cx + cornerX to cy + ry,
            cx - cornerX to cy + ry,
            cx - rx to cy + lowerY,
            cx - rx to cy - upperY,
        )
        return Path().apply {
            pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.first, p.second) else lineTo(p.first, p.second) }
            close()
        }
    }

    /**
     * Draws a cast shadow without ever painting a second badge silhouette.
     *
     * The previous blur-mask fix translated and blurred the complete polygon.
     * With a visible offset that full mask itself could read as a duplicate badge.
     * Here the hard cast-shadow body is path-differenced against the real badge,
     * and the soft component uses OUTER blur only. The real badge fill below is
     * therefore the only complete badge-shaped fill on the frame.
     */
    private fun drawBadgeShadow(
        canvas: Canvas,
        badgePath: Path,
        color: Int,
        radius: Float,
        dx: Float,
        dy: Float,
    ) {
        val shifted = Path(badgePath).apply { offset(dx, dy) }

        if (radius > 0f) {
            paint.resetForShape()
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER)
            canvas.drawPath(shifted, paint)
            paint.maskFilter = null
        }

        if (dx != 0f || dy != 0f) {
            val outside = Path()
            val hasOutside = runCatching {
                outside.op(shifted, badgePath, Path.Op.DIFFERENCE)
            }.getOrDefault(false)
            if (hasOutside && !outside.isEmpty) {
                paint.resetForShape()
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawPath(outside, paint)
            }
        }
        paint.resetForShape()
    }

    private fun drawBadgeShine(canvas: Canvas, badgePath: Path, card: StudioCard, index: Int, frame: Int, local: Int, spec: RendererSpec, cfg: ExactConfig) {
        val shineX = spec.trackWindowed("card.$index.badge.shine.x", frame)
            ?: spec.track("relationships.badge.shine.x", local)
            ?: return
        val trackAlpha = spec.trackWindowed("card.$index.badge.shine.alpha", frame)
            ?: spec.track("relationships.badge.shine.alpha", local)
            ?: 1f
        if (trackAlpha <= 0f) return
        val width = cfg.float("badge.shine.width", 78f).coerceAtLeast(0f)
        val slant = cfg.float("badge.shine.slant", 52f)
        val top = spec.badgeCenterY - cfg.float("badge.radiusY", 177f) - 8f
        val bottom = spec.badgeCenterY + cfg.float("badge.radiusY", 177f) + 8f
        val alphaScale = cfg.float("badge.shine.alpha", 1f).coerceAtLeast(0f) * trackAlpha.coerceIn(0f, 1f)
        val baseAlpha = Color.alpha(spec.shineColor)
        val color = Color.argb((baseAlpha * alphaScale).roundToInt().coerceIn(0, 255), Color.red(spec.shineColor), Color.green(spec.shineColor), Color.blue(spec.shineColor))
        val x = spec.badgeCenterX + shineX
        val shine = Path().apply {
            moveTo(x - width / 2f, top)
            lineTo(x + width / 2f, top)
            lineTo(x + width / 2f + slant, bottom)
            lineTo(x - width / 2f + slant, bottom)
            close()
        }
        canvas.save(); canvas.clipPath(badgePath)
        paint.resetForShape(); paint.color = color
        val feather = cfg.float("badge.shine.feather", 0f).coerceIn(0f, 0.49f)
        if (feather > 0f) {
            val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            paint.shader = LinearGradient(
                x - width / 2f,
                cfg.float("badge.shine.gradientStartY", 0f),
                x + width / 2f,
                cfg.float("badge.shine.gradientEndY", 0f),
                intArrayOf(transparent, color, color, transparent),
                floatArrayOf(0f, feather, 1f - feather, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(shine, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun drawBadgeText(canvas: Canvas, project: StudioProject, card: StudioCard, spec: RendererSpec, cfg: ExactConfig, alpha: Float = 1f) {
        val raw = card.value.trim()
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val primary = parts.firstOrNull().orEmpty()
        val unit = parts.getOrNull(1).orEmpty().ifBlank { cfg.string("badge.defaultUnit", "People") }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = spec.badgeTextColor
        textPaint.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        val shadowRadius = cfg.float("badge.text.shadow.radius", 0f)
        val shadowColor = cfg.color("badge.text.shadow.color", Color.TRANSPARENT)
        if (shadowRadius > 0f && Color.alpha(shadowColor) > 0) {
            textPaint.setShadowLayer(
                shadowRadius,
                cfg.float("badge.text.shadow.dx", 0f),
                cfg.float("badge.text.shadow.dy", 0f),
                shadowColor,
            )
        }
        textPaint.typeface = ProjectFontResolver.resolve(project, typeface(spec, cfg, "badgeHeader", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL), Typeface.NORMAL)
        textPaint.letterSpacing = cfg.float("font.badgeHeader.letterSpacing", cfg.float("font.badge.letterSpacing", 0f))
        drawBadgeLine(canvas, card.badgeHeader.ifBlank { cfg.string("badge.defaultHeader", "1 in") }, spec.badgeCenterX, spec.badgeCenterY + cfg.float("badge.header.y", -75f), spec.badgeHeaderSize, cfg.float("badge.header.minSize", 12f), cfg.float("badge.header.maxWidth", 230f))
        textPaint.typeface = ProjectFontResolver.resolve(project, typeface(spec, cfg, "badgeValue", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL), Typeface.NORMAL)
        textPaint.letterSpacing = cfg.float("font.badgeValue.letterSpacing", cfg.float("font.badge.letterSpacing", 0f))
        drawBadgeLine(canvas, primary, spec.badgeCenterX, spec.badgeCenterY + cfg.float("badge.value.y", 12f), spec.badgeValueSize, cfg.float("badge.value.minSize", 18f), cfg.float("badge.value.maxWidth", 300f))
        textPaint.typeface = ProjectFontResolver.resolve(project, typeface(spec, cfg, "badgeUnit", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL), Typeface.NORMAL)
        textPaint.letterSpacing = cfg.float("font.badgeUnit.letterSpacing", cfg.float("font.badge.letterSpacing", 0f))
        drawBadgeLine(canvas, unit, spec.badgeCenterX, spec.badgeCenterY + cfg.float("badge.unit.y", 70f), spec.badgeUnitSize, cfg.float("badge.unit.minSize", 12f), cfg.float("badge.unit.maxWidth", 245f))
        textPaint.clearShadowLayer()
        textPaint.alpha = 255
        textPaint.letterSpacing = 0f
    }

    private fun drawBadgeLine(canvas: Canvas, text: String, x: Float, y: Float, preferredSize: Float, minimumSize: Float, maxWidth: Float) {
        if (text.isBlank()) return
        textPaint.textSize = preferredSize
        val measured = textPaint.measureText(text).coerceAtLeast(1f)
        textPaint.textSize = if (measured <= maxWidth) preferredSize else (preferredSize * maxWidth / measured).coerceAtLeast(minimumSize)
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawDisclaimer(canvas: Canvas, frame: Int, spec: RendererSpec, cfg: ExactConfig) {
        val first = spec.openingStarts.firstOrNull().orZero()
        val p = ((frame - first) / cfg.float("disclaimer.slideFrames", 70f)).coerceIn(0f, 1f)
        val legacyX = cfg.float("disclaimer.restX", 1450f) + cfg.float("disclaimer.travelX", 470f) * (1f - smooth(p))
        val x = spec.track("relationships.disclaimer.x", frame) ?: legacyX
        val alpha = (spec.track("relationships.disclaimer.alpha", frame) ?: 1f).coerceIn(0f, 1f)
        if (x >= 1920f || alpha <= 0f) return

        paint.resetForShape()
        val background = cfg.color("disclaimer.background", Color.rgb(22, 22, 22))
        val gradientStart = withAlpha(cfg.color("disclaimer.gradient.startColor", background), alpha)
        val gradientEnd = withAlpha(cfg.color("disclaimer.gradient.endColor", background), alpha)
        if (cfg.has("disclaimer.gradient.startColor") || cfg.has("disclaimer.gradient.endColor")) {
            paint.shader = LinearGradient(
                cfg.float("disclaimer.gradient.startX", x),
                cfg.float("disclaimer.gradient.startY", 0f),
                cfg.float("disclaimer.gradient.endX", 1920f),
                cfg.float("disclaimer.gradient.endY", 0f),
                gradientStart,
                gradientEnd,
                Shader.TileMode.CLAMP,
            )
        } else {
            paint.color = withAlpha(background, alpha)
        }
        canvas.drawRect(x, 0f, 1920f, 1080f, paint)
        paint.shader = null
        val borderWidth = cfg.float("disclaimer.border.width", 0f)
        if (borderWidth > 0f) {
            paint.resetForShape()
            paint.color = withAlpha(cfg.color("disclaimer.border.color", Color.rgb(74, 74, 74)), alpha)
            canvas.drawRect(x, 0f, x + borderWidth, 1080f, paint)
        }

        val left = x + cfg.float("disclaimer.padX", 30f)
        var y = cfg.float("disclaimer.y", 220f)
        val lineGap = cfg.float("disclaimer.lineGap", 38f)
        val header = cfg.string("disclaimer.header", "DISCLAIMER:")
        val lines = cfg.string("disclaimer.lines", "This|comparison video|is based on public|data, surveys,|public comments|& discussions and|approximate|estimations that|might be|subjected to some|degree of error.").split('|')

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = typeface(spec, cfg, "disclaimer", "sans-serif", Typeface.NORMAL)
        textPaint.textSize = cfg.float("disclaimer.textSize", 26f)
        textPaint.letterSpacing = cfg.float("font.disclaimer.letterSpacing", 0f)
        if (header.isNotBlank()) {
            textPaint.color = withAlpha(cfg.color("disclaimer.headerColor", Color.rgb(178, 0, 22)), alpha)
            canvas.drawText(header, left, y, textPaint)
        }
        if (lines.isNotEmpty()) {
            val bodyX = left + if (header.isBlank()) 0f else textPaint.measureText(header) + cfg.float("disclaimer.headerGap", 9f)
            textPaint.color = withAlpha(cfg.color("disclaimer.textColor", Color.LTGRAY), alpha)
            canvas.drawText(lines.first(), bodyX, y, textPaint)
            lines.drop(1).forEach { line ->
                y += lineGap
                canvas.drawText(line, left, y, textPaint)
            }
        }
        textPaint.letterSpacing = 0f
    }

    private fun drawOutro(
        canvas: Canvas,
        project: StudioProject,
        frame: Int,
        contentEnd: Int,
        spec: RendererSpec,
        cfg: ExactConfig,
        ledger: RenderPassLedger,
    ) {
        val local = frame - contentEnd
        val last = project.cards.last()
        val lastIndex = project.cards.lastIndex
        val cardX = spec.track("relationships.outro.card.x", frame) ?: when {
            local < 80 -> lerp(320f, 781f, smooth(local / 80f))
            else -> 781f
        }
        ledger.once("card.$lastIndex.body") { drawCardBody(canvas, project, last, cardX, spec, cfg, frame, lastIndex) }
        ledger.once("card.$lastIndex.badge") { drawBadge(canvas, project, lastIndex, cardX, frame, spec, cfg) }

        val panelAlpha = (spec.track("relationships.outro.panel.alpha", frame) ?: if (local >= cfg.int("outro.panel.start", 58)) 1f else 0f).coerceIn(0f, 1f)
        if (panelAlpha > 0f) {
            val left = cfg.float("outro.panel.left", 1290f)
            val top = cfg.float("outro.panel.top", 180f)
            val right = cfg.float("outro.panel.right", 1732f)
            val bottom = cfg.float("outro.panel.bottom", 910f)
            val radius = cfg.float("outro.panel.radius", 0f).coerceAtLeast(0f)
            paint.resetForShape(); paint.color = withAlpha(cfg.color("outro.panel.color", Color.rgb(28, 28, 28)), panelAlpha)
            val panel = RectF(left, top, right, bottom)
            if (radius > 0f) canvas.drawRoundRect(panel, radius, radius, paint) else canvas.drawRect(panel, paint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = withAlpha(cfg.color("outro.panel.labelColor", Color.rgb(145, 145, 145)), panelAlpha)
            textPaint.textSize = cfg.float("outro.panel.labelSize", 31f)
            textPaint.typeface = typeface(spec, cfg, "outroPanel", "sans-serif-light", Typeface.NORMAL)
            textPaint.letterSpacing = cfg.float("font.outroPanel.letterSpacing", 0f)
            canvas.drawText(cfg.string("outro.panel.label", "WATCH MORE"), cfg.float("outro.panel.labelX", (left + right) / 2f), cfg.float("outro.panel.labelY", 235f), textPaint)
            textPaint.letterSpacing = 0f
        }

        val question = cfg.string("outro.question", "Which relationship type\\nare you in right now?").replace("\\n", "\n")
        val questionChars = spec.track("relationships.outro.question.chars", frame)?.roundToInt()
            ?: (((local - cfg.int("outro.question.start", 70)).coerceAtLeast(0) * cfg.float("outro.question.charsPerFrame", 0.52f)).toInt())
        if (questionChars > 0) drawTyped(
            canvas,
            question.take(questionChars.coerceIn(0, question.length)),
            cfg.float("outro.question.x", 40f),
            cfg.float("outro.question.y", 390f),
            cfg.float("outro.question.size", 37f),
            cfg.color("outro.question.color", Color.WHITE),
            typeface(spec, cfg, "outroQuestion", "sans-serif", Typeface.BOLD),
            cfg.float("outro.question.lineGap", 7f),
            cfg.float("font.outroQuestion.letterSpacing", 0f),
        )

        val comment = cfg.string("outro.comment", "Comment below!")
        val commentChars = spec.track("relationships.outro.comment.chars", frame)?.roundToInt()
            ?: (((local - cfg.int("outro.comment.start", 225)).coerceAtLeast(0) * cfg.float("outro.comment.charsPerFrame", 0.6f)).toInt())
        if (commentChars > 0) drawTyped(
            canvas,
            comment.take(commentChars.coerceIn(0, comment.length)),
            cfg.float("outro.comment.x", 40f),
            cfg.float("outro.comment.y", 500f),
            cfg.float("outro.comment.size", 37f),
            cfg.color("outro.comment.color", Color.rgb(244, 159, 0)),
            typeface(spec, cfg, "outroComment", "sans-serif", Typeface.NORMAL),
            cfg.float("outro.comment.lineGap", 7f),
            cfg.float("font.outroComment.letterSpacing", 0f),
        )

        val subscribeAlpha = (spec.track("relationships.outro.subscribe.alpha", frame) ?: if (local >= cfg.int("outro.subscribe.start", 290)) 1f else 0f).coerceIn(0f, 1f)
        if (subscribeAlpha > 0f) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = typeface(spec, cfg, "outroSubscribe", "sans-serif", Typeface.BOLD)
            textPaint.letterSpacing = cfg.float("font.outroSubscribe.letterSpacing", 0f)
            textPaint.textSize = cfg.float("outro.subscribe.size", 34f)
            textPaint.color = withAlpha(cfg.color("outro.subscribe.color", Color.rgb(224, 10, 34)), subscribeAlpha)
            canvas.drawText(cfg.string("outro.subscribe.text", "SUBSCRIBE"), cfg.float("outro.subscribe.x", 40f), cfg.float("outro.subscribe.y", 900f), textPaint)
            textPaint.typeface = typeface(spec, cfg, "outroSubscribeRest", "sans-serif-light", Typeface.NORMAL)
            textPaint.letterSpacing = cfg.float("font.outroSubscribeRest.letterSpacing", 0f)
            textPaint.color = withAlpha(cfg.color("outro.subscribe.restColor", Color.LTGRAY), subscribeAlpha)
            textPaint.textSize = cfg.float("outro.subscribe.restSize", 30f)
            canvas.drawText(cfg.string("outro.subscribe.rest1", "for more"), cfg.float("outro.subscribe.rest1X", 220f), cfg.float("outro.subscribe.y", 900f), textPaint)
            canvas.drawText(cfg.string("outro.subscribe.rest2", "comparison videos."), cfg.float("outro.subscribe.x", 40f), cfg.float("outro.subscribe.rest2Y", 944f), textPaint)
            textPaint.letterSpacing = 0f
        }

        val trackedFade = spec.track("relationships.outro.fade.alpha", frame)
        val fadeAlpha = if (trackedFade != null) trackedFade.coerceIn(0f, 1f) else {
            val fadeStart = max(0, RelationshipsTimeline.totalFrameCount(project, spec) - contentEnd - 42)
            if (local >= fadeStart) ((local - fadeStart) / 42f).coerceIn(0f, 1f) else 0f
        }
        if (fadeAlpha > 0f) {
            paint.resetForShape(); paint.color = Color.argb((fadeAlpha * 255).roundToInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawRect(0f, 0f, 1920f, 1080f, paint)
        }
    }

    private fun drawTyped(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        face: Typeface,
        lineGap: Float,
        letterSpacing: Float = 0f,
    ) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = face
        textPaint.textSize = size
        textPaint.color = color
        textPaint.letterSpacing = letterSpacing
        text.split('\n').forEachIndexed { i, line -> canvas.drawText(line, x, y + i * (size + lineGap), textPaint) }
        textPaint.letterSpacing = 0f
    }

    private fun drawFitted(
        canvas: Canvas,
        text: String,
        box: RectF,
        color: Int,
        preferred: Float,
        face: Typeface,
        maxLines: Int,
        lineHeightScale: Float,
        alpha: Float = 1f,
        letterSpacing: Float = 0f,
    ) {
        textPaint.color = color
        textPaint.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        textPaint.typeface = face
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.letterSpacing = letterSpacing
        var size = preferred
        var lines = wrap(text, box.width(), size, maxLines)
        while ((lines.size > maxLines || lines.any { measure(it, size) > box.width() }) && size > 8f) {
            size -= 0.5f
            lines = wrap(text, box.width(), size, maxLines)
        }
        textPaint.textSize = size
        val fm = textPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * lineHeightScale
        var y = box.centerY() - (lines.size - 1) * lineHeight / 2f - (fm.ascent + fm.descent) / 2f
        lines.take(maxLines).forEach { canvas.drawText(it, box.centerX(), y, textPaint); y += lineHeight }
        textPaint.alpha = 255
        textPaint.letterSpacing = 0f
    }

    private fun wrap(text: String, width: Float, size: Float, maxLines: Int): List<String> {
        textPaint.textSize = size
        val lines = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) {
                lines += ""
            } else {
                var current = ""
                words.forEach { word ->
                    val trial = if (current.isBlank()) word else "$current $word"
                    if (textPaint.measureText(trial) <= width || current.isBlank()) {
                        current = trial
                    } else {
                        lines += current
                        current = word
                    }
                }
                if (current.isNotBlank()) lines += current
            }
        }
        if (lines.size <= maxLines) return lines
        return lines.take(maxLines - 1) + lines.drop(maxLines - 1).joinToString(" ")
    }

    private fun measure(text: String, size: Float): Float {
        textPaint.textSize = size
        return textPaint.measureText(text)
    }

    private fun typeface(spec: RendererSpec, cfg: ExactConfig, role: String, fallbackFamily: String, fallbackStyle: Int): Typeface {
        val asset = cfg.stringOrNull("font.$role.asset")
        if (asset != null) {
            val encoded = cfg.stringOrNull("font.asset.$asset.base64")
            if (!encoded.isNullOrBlank()) {
                val cacheKey = "${spec.id}:$asset:${encoded.hashCode()}"
                typefaceCache[cacheKey]?.let { return it }
                val built = runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    val temp = File.createTempFile("cc-renderer-font-", ".font")
                    try {
                        temp.writeBytes(bytes)
                        Typeface.createFromFile(temp)
                    } finally {
                        temp.delete()
                    }
                }.getOrNull()
                if (built != null) {
                    if (typefaceCache.size >= 8) typefaceCache.remove(typefaceCache.keys.first())
                    typefaceCache[cacheKey] = built
                    return built
                }
            }
        }
        val family = cfg.string("font.$role.family", fallbackFamily)
        val style = when (cfg.string("font.$role.style", "").lowercase()) {
            "bold" -> Typeface.BOLD
            "italic" -> Typeface.ITALIC
            "bolditalic", "bold_italic", "bold-italic" -> Typeface.BOLD_ITALIC
            "normal" -> Typeface.NORMAL
            else -> fallbackStyle
        }
        return Typeface.create(family, style)
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color),
    )

    private fun Paint.resetForShape() {
        reset()
        isAntiAlias = true
        isFilterBitmap = true
        style = Paint.Style.FILL
        alpha = 255
        shader = null
    }

    private fun smooth(x: Float): Float {
        val p = x.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p.coerceIn(0f, 1f)
    private fun Int?.orZero() = this ?: 0

    private class ExactConfig(spec: RendererSpec) {
        private val values: Map<String, String> = buildMap {
            spec.tags.forEach { raw ->
                val split = raw.indexOf('=')
                if (split > 0) put(raw.substring(0, split).trim(), raw.substring(split + 1))
            }
        }
        private val binaryCache = mutableMapOf<String, ByteArray?>()

        fun has(key: String): Boolean = values.containsKey(key)
        fun string(key: String, fallback: String): String = values[key] ?: fallback
        fun stringOrNull(key: String): String? = values[key]
        fun bool(key: String, fallback: Boolean): Boolean = values[key]?.trim()?.lowercase()?.let {
            when (it) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> fallback
            }
        } ?: fallback
        fun int(key: String, fallback: Int): Int = values[key]?.trim()?.toIntOrNull() ?: fallback
        fun float(key: String, fallback: Float): Float = values[key]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback
        fun color(key: String, fallback: Int): Int {
            val raw = values[key]?.trim() ?: return fallback
            return runCatching {
                when {
                    raw.startsWith("#") -> Color.parseColor(raw)
                    raw.startsWith("0x", true) -> raw.substring(2).toLong(16).toInt()
                    else -> raw.toLong().toInt()
                }
            }.getOrDefault(fallback)
        }
        fun floatList(key: String): List<Float> = values[key]
            ?.split(',')
            ?.mapNotNull { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) }
            .orEmpty()

        fun gzipBase64(key: String): ByteArray? {
            if (binaryCache.containsKey(key)) return binaryCache[key]
            val decoded = runCatching {
                val encoded = values[key] ?: return@runCatching null
                val compressed = Base64.decode(encoded, Base64.DEFAULT)
                GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            }.getOrNull()
            binaryCache[key] = decoded
            return decoded
        }
    }

    companion object {
        fun enabled(spec: RendererSpec): Boolean = spec.tags.any {
            it.trim().equals("relationships.exact.v2=true", ignoreCase = true) ||
                it.trim().equals("relationships.exact.v2=1", ignoreCase = true)
        }
    }
}
