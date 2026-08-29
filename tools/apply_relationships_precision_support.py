from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"Patch point not found: {label}")
    return text.replace(old, new, 1)


def replace_region(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise RuntimeError(f"Start marker not found: {label}")
    end = text.find(end_marker, start)
    if end < 0:
        raise RuntimeError(f"End marker not found: {label}")
    return text[:start] + replacement + text[end:]


# 1. Advertise the capability so exact bundles cannot silently load on an older app.
bundle = SRC / "RendererBundle.kt"
text = bundle.read_text()
if '"relationships-exact-v2"' not in text:
    text = replace_once(
        text,
        '        "frame-addressed-shine",\n',
        '        "frame-addressed-shine",\n        "relationships-exact-v2",\n',
        "relationships-exact-v2 capability",
    )
bundle.write_text(text)


# 2. Preserve the reference video's BT.709 SDR limited/video-range metadata in hardware exports.
exporter = SRC / "HardwareVideoExporter.kt"
text = exporter.read_text()
if "COLOR_STANDARD_BT709" not in text:
    needle = "            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)\n"
    insertion = needle + """            if (RelationshipsPrecisionFrameRenderer.enabled(RendererRuntime.active) && Build.VERSION.SDK_INT >= 24) {
                // The measured Relationships reference is SDR BT.709 with limited/video range.
                // Do not leave the RGB->YUV signalling device-dependent for frame-exact exports.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
"""
    text = replace_once(text, needle, insertion, "BT.709 export metadata")
exporter.write_text(text)


renderer = SRC / "RelationshipsPrecisionFrameRenderer.kt"
text = renderer.read_text()

# 3. Android Typeface.Builder does not accept a ByteBuffer. Materialise the declarative font bytes
# into a private temporary file, create a Typeface from it, then remove the file immediately.
old_font = """                val built = runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    Typeface.Builder(ByteBuffer.wrap(bytes)).build()
                }.getOrNull()
"""
new_font = """                val built = runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    val temp = File.createTempFile("cc-renderer-font-", ".font")
                    try {
                        temp.writeBytes(bytes)
                        Typeface.createFromFile(temp)
                    } finally {
                        temp.delete()
                    }
                }.getOrNull()
"""
text = replace_once(text, old_font, new_font, "embedded typeface loader")

# 4. The source intro remains visible beneath the first cards; keep that overlap frame-addressable.
old_content_dispatch = "            frame < contentEnd -> drawContent(canvas, project, frame, spec, cfg)\n"
new_content_dispatch = """            frame < contentEnd -> {
                if (frame < cfg.int("intro.overlayUntilFrame", spec.openingStarts.firstOrNull().orZero())) {
                    drawIntroLogo(canvas, frame, spec, cfg)
                }
                drawContent(canvas, project, frame, spec, cfg)
            }
"""
text = replace_once(text, old_content_dispatch, new_content_dispatch, "intro/card overlap")

# 5. Replace the intro renderer with a layered, declarative version. A single layer reproduces
# legacy behaviour; measured renderers can provide multiple glow/stroke layers.
intro_start = "    private fun drawIntroLogo(canvas: Canvas, frame: Int, spec: RendererSpec, cfg: ExactConfig) {\n"
intro_end = "    private fun drawContent(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec, cfg: ExactConfig) {\n"
new_intro = r'''    private fun drawIntroLogo(canvas: Canvas, frame: Int, spec: RendererSpec, cfg: ExactConfig) {
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
            textPaint.textSize = cfg.float("intro.text.size", 34f)
            val y = cfg.float("intro.text.y", 640f)
            val lineGap = cfg.float("intro.text.lineGap", 38f)
            visible.split('\n').forEachIndexed { index, line -> canvas.drawText(line, cx, y + index * lineGap, textPaint) }
            textPaint.alpha = 255
        }
    }

'''
text = replace_region(text, intro_start, intro_end, new_intro, "layered intro renderer")

# 6. Make exact panel geometry, corner radii, divider, and opening text reveal declarative.
body_start = "    private fun drawCardBody(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec, cfg: ExactConfig, frame: Int, index: Int) {\n"
body_end = "    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec, cfg: ExactConfig) {\n"
new_body = r'''    private fun drawCardBody(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec, cfg: ExactConfig, frame: Int, index: Int) {
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
        val reveal = (spec.track("card.$index.body.reveal", frame)
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
            val titleAlpha = (spec.track("card.$index.title.alpha", frame)
                ?: spec.track("relationships.card.title.alpha", local)
                ?: 1f).coerceIn(0f, 1f)
            val titleChars = spec.track("card.$index.title.chars", frame)?.roundToInt()
                ?: spec.track("relationships.card.title.chars", local)?.roundToInt()
            val titleText = if (titleChars == null) card.title else card.title.take(titleChars.coerceIn(0, card.title.length))
            if (titleText.isNotBlank() && titleAlpha > 0f) {
                drawFitted(
                    canvas, titleText,
                    RectF(left + cfg.float("card.title.padX", 10f), titleTop + cfg.float("card.title.padTop", 1f), right - cfg.float("card.title.padX", 10f), titleBottom - cfg.float("card.title.padBottom", 1f)),
                    spec.titleTextColor, spec.titleTextSize,
                    typeface(spec, cfg, "title", "sans-serif", Typeface.BOLD),
                    cfg.int("card.title.maxLines", 1), cfg.float("card.title.lineHeight", 0.92f), titleAlpha,
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
            val descriptionAlpha = (spec.track("card.$index.description.alpha", frame)
                ?: spec.track("relationships.card.description.alpha", local)
                ?: 1f).coerceIn(0f, 1f)
            val descriptionChars = spec.track("card.$index.description.chars", frame)?.roundToInt()
                ?: spec.track("relationships.card.description.chars", local)?.roundToInt()
            val descriptionText = if (descriptionChars == null) card.description else card.description.take(descriptionChars.coerceIn(0, card.description.length))
            if (descriptionText.isNotBlank() && descriptionAlpha > 0f) {
                drawFitted(
                    canvas, descriptionText,
                    RectF(left + cfg.float("card.description.padX", 11f), descriptionTop + cfg.float("card.description.padTop", 4f), right - cfg.float("card.description.padX", 11f), 1080f - cfg.float("card.description.padBottom", 4f)),
                    spec.descriptionTextColor, spec.descriptionTextSize,
                    typeface(spec, cfg, "description", "sans-serif", Typeface.NORMAL),
                    cfg.int("card.description.maxLines", 4), cfg.float("card.description.lineHeight", 0.92f), descriptionAlpha,
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

'''
text = replace_region(text, body_start, body_end, new_body, "exact card panels and text reveal")

# 7. Front artwork uses the same measured rounded image mask.
front_start = "    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec, cfg: ExactConfig) {\n"
front_end = "    private fun drawArtwork(canvas: Canvas, card: StudioCard, destination: RectF) {\n"
new_front = r'''    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec, cfg: ExactConfig) {
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

'''
text = replace_region(text, front_start, front_end, new_front, "front artwork mask")

# 8. Badge shape/fill already supports arbitrary points/gradient/shadow. Make its outline layered,
# and make badge text visibility frame-addressable without Paint alpha being overwritten.
badge_start = "    private fun drawBadge(canvas: Canvas, project: StudioProject, index: Int, cardX: Float, frame: Int, spec: RendererSpec, cfg: ExactConfig) {\n"
badge_end = "    private fun badgePath(cfg: ExactConfig, cx: Float, cy: Float): Path {\n"
new_badge = r'''    private fun drawBadge(canvas: Canvas, project: StudioProject, index: Int, cardX: Float, frame: Int, spec: RendererSpec, cfg: ExactConfig) {
        if (!project.showBadges) return
        val card = project.cards[index]
        if (card.value.isBlank() && card.badgeHeader.isBlank()) return
        val entry = RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec)
        val local = frame - entry
        if (local < 0) return

        val scale = (spec.track("card.$index.badge.scale", frame)
            ?: spec.track("relationships.badge.scale", local)
            ?: if (local < 45) smooth(local / 45f) else 1f).coerceIn(0f, cfg.float("badge.maxScale", 2f))
        val yOffset = spec.track("card.$index.badge.y", frame) ?: spec.track("relationships.badge.y", local) ?: 0f
        val xOffset = spec.track("card.$index.badge.x", frame) ?: 0f
        val cx = spec.badgeCenterX
        val cy = spec.badgeCenterY

        canvas.save()
        canvas.translate(cardX + xOffset, yOffset)
        canvas.scale(scale * spec.badgeScale, scale * spec.badgeScale, cx, cy)
        val path = badgePath(cfg, cx, cy)

        val shadowColor = cfg.color("badge.shadow.color", Color.TRANSPARENT)
        val shadowRadius = cfg.float("badge.shadow.radius", 0f)
        paint.resetForShape()
        if (Color.alpha(shadowColor) > 0 && shadowRadius > 0f) {
            paint.color = spec.badgeColor
            paint.setShadowLayer(shadowRadius, cfg.float("badge.shadow.dx", 0f), cfg.float("badge.shadow.dy", 0f), shadowColor)
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()
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
        val textAlpha = (spec.track("card.$index.badge.text.alpha", frame)
            ?: spec.track("relationships.badge.text.alpha", local)
            ?: 1f).coerceIn(0f, 1f)
        if (textAlpha > 0f) drawBadgeText(canvas, card, spec, cfg, textAlpha)
        canvas.restore()
        paint.resetForShape()
    }

'''
text = replace_region(text, badge_start, badge_end, new_badge, "layered badge renderer")

badge_text_start = "    private fun drawBadgeText(canvas: Canvas, card: StudioCard, spec: RendererSpec, cfg: ExactConfig) {\n"
badge_text_end = "    private fun drawBadgeLine(\n"
new_badge_text = r'''    private fun drawBadgeText(canvas: Canvas, card: StudioCard, spec: RendererSpec, cfg: ExactConfig, alpha: Float = 1f) {
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
        textPaint.typeface = typeface(spec, cfg, "badgeHeader", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL)
        drawBadgeLine(canvas, card.badgeHeader.ifBlank { cfg.string("badge.defaultHeader", "1 in") }, spec.badgeCenterX, spec.badgeCenterY + cfg.float("badge.header.y", -75f), spec.badgeHeaderSize, cfg.float("badge.header.minSize", 12f), cfg.float("badge.header.maxWidth", 230f))
        textPaint.typeface = typeface(spec, cfg, "badgeValue", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL)
        drawBadgeLine(canvas, primary, spec.badgeCenterX, spec.badgeCenterY + cfg.float("badge.value.y", 12f), spec.badgeValueSize, cfg.float("badge.value.minSize", 18f), cfg.float("badge.value.maxWidth", 300f))
        textPaint.typeface = typeface(spec, cfg, "badgeUnit", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL)
        drawBadgeLine(canvas, unit, spec.badgeCenterX, spec.badgeCenterY + cfg.float("badge.unit.y", 70f), spec.badgeUnitSize, cfg.float("badge.unit.minSize", 12f), cfg.float("badge.unit.maxWidth", 245f))
        textPaint.clearShadowLayer()
        textPaint.alpha = 255
    }

'''
text = replace_region(text, badge_text_start, badge_text_end, new_badge_text, "badge text roles/shadow")

# 9. Preserve fractional alpha after setting ARGB text colour.
fitted_start = "    private fun drawFitted(canvas: Canvas, text: String, box: RectF, color: Int, preferred: Float, face: Typeface, maxLines: Int, lineHeightScale: Float) {\n"
fitted_end = "    private fun wrap(text: String, width: Float, size: Float, maxLines: Int): List<String> {\n"
new_fitted = r'''    private fun drawFitted(
        canvas: Canvas,
        text: String,
        box: RectF,
        color: Int,
        preferred: Float,
        face: Typeface,
        maxLines: Int,
        lineHeightScale: Float,
        alpha: Float = 1f,
    ) {
        textPaint.color = color
        textPaint.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        textPaint.typeface = face
        textPaint.textAlign = Paint.Align.CENTER
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
    }

'''
text = replace_region(text, fitted_start, fitted_end, new_fitted, "fitted text alpha")

renderer.write_text(text)

print("Relationships exact-v2 source patch applied.")
