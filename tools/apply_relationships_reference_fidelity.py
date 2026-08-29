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


bundle = SRC / "RendererBundle.kt"
text = bundle.read_text()
if '"relationships-footer-waveform"' not in text:
    text = replace_once(
        text,
        '        "relationships-exact-v2",\n',
        '        "relationships-exact-v2",\n        "relationships-footer-waveform",\n        "relationships-rich-typography",\n',
        "final exact capability flags",
    )
bundle.write_text(text)

renderer = SRC / "RelationshipsPrecisionFrameRenderer.kt"
text = renderer.read_text()

text = replace_once(text, "import java.io.File\n", "import java.io.ByteArrayInputStream\nimport java.io.File\n", "binary stream import")
text = replace_once(text, "import java.util.LinkedHashMap\n", "import java.util.LinkedHashMap\nimport java.util.zip.GZIPInputStream\n", "gzip import")

# Parsing very large base64 renderer tags once per frame would allocate megabytes repeatedly.
# Cache the immutable tag view for the active renderer instead.
fields_old = '''    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    @Synchronized
'''
fields_new = '''    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
'''
text = replace_once(text, fields_old, fields_new, "renderer tag cache")
text = replace_once(text, "        val cfg = ExactConfig(spec)\n", "        val cfg = exactConfig(spec)\n", "cached exact config")

# The tiny animated strip at the bottom of the source is a background layer, so draw it before
# cards/logo and let foreground content occlude it naturally.
text = replace_once(
    text,
    "        canvas.drawColor(spec.backgroundColor)\n        if (project.cards.isEmpty()) {\n",
    "        canvas.drawColor(spec.backgroundColor)\n        drawFooterWaveform(canvas, frame, cfg)\n        if (project.cards.isEmpty()) {\n",
    "footer waveform draw order",
)

footer_marker = "    private fun drawIntroLogo(canvas: Canvas, frame: Int, spec: RendererSpec, cfg: ExactConfig) {\n"
footer_fn = r'''    private fun drawFooterWaveform(canvas: Canvas, frame: Int, cfg: ExactConfig) {
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

'''
if footer_fn not in text:
    idx = text.find(footer_marker)
    if idx < 0:
        raise RuntimeError("Intro marker not found for footer insertion")
    text = text[:idx] + footer_fn + text[idx:]

# Exact font tracking for intro text.
text = replace_once(
    text,
    '            textPaint.typeface = typeface(spec, cfg, "intro", "sans-serif-light", Typeface.NORMAL)\n            textPaint.textSize = cfg.float("intro.text.size", 34f)\n',
    '            textPaint.typeface = typeface(spec, cfg, "intro", "sans-serif-light", Typeface.NORMAL)\n            textPaint.letterSpacing = cfg.float("font.intro.letterSpacing", 0f)\n            textPaint.textSize = cfg.float("intro.text.size", 34f)\n',
    "intro letter spacing",
)
text = replace_once(text, "            textPaint.alpha = 255\n        }\n    }\n\n    private fun drawContent", "            textPaint.alpha = 255\n            textPaint.letterSpacing = 0f\n        }\n    }\n\n    private fun drawContent", "reset intro tracking")

# Give each badge line its own font and tracking while keeping the measured shadow.
badge_text_start = "    private fun drawBadgeText(canvas: Canvas, card: StudioCard, spec: RendererSpec, cfg: ExactConfig, alpha: Float = 1f) {\n"
badge_text_end = "    private fun drawBadgeLine(canvas: Canvas, text: String, x: Float, y: Float, preferredSize: Float, minimumSize: Float, maxWidth: Float) {\n"
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
        textPaint.letterSpacing = cfg.float("font.badgeHeader.letterSpacing", cfg.float("font.badge.letterSpacing", 0f))
        drawBadgeLine(canvas, card.badgeHeader.ifBlank { cfg.string("badge.defaultHeader", "1 in") }, spec.badgeCenterX, spec.badgeCenterY + cfg.float("badge.header.y", -75f), spec.badgeHeaderSize, cfg.float("badge.header.minSize", 12f), cfg.float("badge.header.maxWidth", 230f))
        textPaint.typeface = typeface(spec, cfg, "badgeValue", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL)
        textPaint.letterSpacing = cfg.float("font.badgeValue.letterSpacing", cfg.float("font.badge.letterSpacing", 0f))
        drawBadgeLine(canvas, primary, spec.badgeCenterX, spec.badgeCenterY + cfg.float("badge.value.y", 12f), spec.badgeValueSize, cfg.float("badge.value.minSize", 18f), cfg.float("badge.value.maxWidth", 300f))
        textPaint.typeface = typeface(spec, cfg, "badgeUnit", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL)
        textPaint.letterSpacing = cfg.float("font.badgeUnit.letterSpacing", cfg.float("font.badge.letterSpacing", 0f))
        drawBadgeLine(canvas, unit, spec.badgeCenterX, spec.badgeCenterY + cfg.float("badge.unit.y", 70f), spec.badgeUnitSize, cfg.float("badge.unit.minSize", 12f), cfg.float("badge.unit.maxWidth", 245f))
        textPaint.clearShadowLayer()
        textPaint.alpha = 255
        textPaint.letterSpacing = 0f
    }

'''
text = replace_region(text, badge_text_start, badge_text_end, new_badge_text, "badge tracking")

# Source disclaimer: red prefix and white body text share the first baseline, over a gradient panel.
disclaimer_start = "    private fun drawDisclaimer(canvas: Canvas, frame: Int, spec: RendererSpec, cfg: ExactConfig) {\n"
disclaimer_end = "    private fun drawOutro(canvas: Canvas, project: StudioProject, frame: Int, contentEnd: Int, spec: RendererSpec, cfg: ExactConfig) {\n"
new_disclaimer = r'''    private fun drawDisclaimer(canvas: Canvas, frame: Int, spec: RendererSpec, cfg: ExactConfig) {
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

'''
text = replace_region(text, disclaimer_start, disclaimer_end, new_disclaimer, "rich disclaimer")

# Outro keeps the footer behind it, supports source-rounded panel, and distinct font roles.
outro_start = "    private fun drawOutro(canvas: Canvas, project: StudioProject, frame: Int, contentEnd: Int, spec: RendererSpec, cfg: ExactConfig) {\n"
outro_end = "    private fun drawTyped(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, face: Typeface, lineGap: Float) {\n"
new_outro = r'''    private fun drawOutro(canvas: Canvas, project: StudioProject, frame: Int, contentEnd: Int, spec: RendererSpec, cfg: ExactConfig) {
        val local = frame - contentEnd
        val last = project.cards.last()
        val cardX = spec.track("relationships.outro.card.x", frame) ?: when {
            local < 80 -> lerp(320f, 781f, smooth(local / 80f))
            else -> 781f
        }
        drawCardBody(canvas, last, cardX, spec, cfg, frame, project.cards.lastIndex)
        drawBadge(canvas, project, project.cards.lastIndex, cardX, frame, spec, cfg)

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

'''
text = replace_region(text, outro_start, outro_end, new_outro, "source outro")

# Typed text also needs tracking.
typed_start = "    private fun drawTyped(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, face: Typeface, lineGap: Float) {\n"
typed_end = "    private fun drawFitted(\n"
new_typed = r'''    private fun drawTyped(
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

'''
text = replace_region(text, typed_start, typed_end, new_typed, "typed text tracking")

# Fitted card text supports exact tracking and preserves explicit source line breaks.
fitted_start = "    private fun drawFitted(\n"
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

'''
text = replace_region(text, fitted_start, fitted_end, new_fitted, "fitted text tracking")

wrap_start = "    private fun wrap(text: String, width: Float, size: Float, maxLines: Int): List<String> {\n"
wrap_end = "    private fun measure(text: String, size: Float): Float {\n"
new_wrap = r'''    private fun wrap(text: String, width: Float, size: Float, maxLines: Int): List<String> {
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

'''
text = replace_region(text, wrap_start, wrap_end, new_wrap, "explicit line breaks")

# Add card title/description tracking at their fitted calls.
text = replace_once(
    text,
    '                    cfg.int("card.title.maxLines", 1), cfg.float("card.title.lineHeight", 0.92f), titleAlpha,\n',
    '                    cfg.int("card.title.maxLines", 1), cfg.float("card.title.lineHeight", 0.92f), titleAlpha, cfg.float("font.title.letterSpacing", 0f),\n',
    "title tracking call",
)
text = replace_once(
    text,
    '                    cfg.int("card.description.maxLines", 4), cfg.float("card.description.lineHeight", 0.92f), descriptionAlpha,\n',
    '                    cfg.int("card.description.maxLines", 4), cfg.float("card.description.lineHeight", 0.92f), descriptionAlpha, cfg.float("font.description.letterSpacing", 0f),\n',
    "description tracking call",
)

# Extend ExactConfig with a cached gzip/base64 binary accessor. This is used for the measured
# 11,130-frame footer strip and avoids rebuilding/decompressing it while exporting.
config_start = "    private class ExactConfig(spec: RendererSpec) {\n"
config_end = "    companion object {\n"
new_config = r'''    private class ExactConfig(spec: RendererSpec) {
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

'''
text = replace_region(text, config_start, config_end, new_config, "cached binary renderer data")

renderer.write_text(text)
print("Final relationships reference-fidelity patch applied.")
