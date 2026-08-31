#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/InfiniteTimelineFrameRenderer.kt"


def replace_section(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"{label}: start marker not found")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"{label}: end marker not found")
    return text[:a] + replacement.rstrip() + "\n\n" + text[b:]


text = TARGET.read_text()
if "SOURCE_EXACT_V2 = true" in text:
    print("Infinite source-exact v2 already applied")
    raise SystemExit(0)

text = text.replace(
    "import android.graphics.Path\n",
    "import android.graphics.Path\nimport android.graphics.PathMeasure\nimport android.graphics.BlurMaskFilter\n",
    1,
)

text = replace_section(
    text,
    "    private fun drawReference(",
    "    private fun drawBrandIntro(",
    '''    private fun drawReference(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        canvas.drawColor(backgroundForFrame(spec, frame))
        if (frame < spec.continuousStartFrame) {
            drawBrandIntro(canvas, frame)
            drawDisclaimer(canvas, frame)
        }
        if (project.cards.isEmpty()) return

        val outroStart = InfiniteTimeline.outroStartFrame(project, spec)
        when {
            frame < spec.continuousStartFrame -> drawOpening(canvas, project, frame, spec)
            frame < outroStart -> drawConveyor(canvas, project, frame, spec)
            else -> drawOutro(canvas, project, frame, outroStart, spec)
        }
    }

    private fun backgroundForFrame(spec: RendererSpec, frame: Int): Int {
        if (frame < spec.continuousStartFrame) return spec.backgroundColor
        if (frame < 5260) return CONTINUOUS_BACKGROUND
        val p = spec.track("infinite.outro.bg.progress", frame)
            ?: ((frame - 5260) / 150f).coerceIn(0f, 1f)
        return Color.rgb(
            lerpColor(Color.red(CONTINUOUS_BACKGROUND), Color.red(spec.backgroundColor), p),
            lerpColor(Color.green(CONTINUOUS_BACKGROUND), Color.green(spec.backgroundColor), p),
            lerpColor(Color.blue(CONTINUOUS_BACKGROUND), Color.blue(spec.backgroundColor), p),
        )
    }

    private fun lerpColor(a: Int, b: Int, p: Float): Int =
        (a + (b - a) * p.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
''',
    "reference phase dispatch",
)

text = replace_section(
    text,
    "    private fun drawOpening(",
    "    private fun drawConveyor(",
    '''    private fun drawOpening(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        for (index in 0 until min(4, project.cards.size)) {
            val start = spec.openingStarts.getOrElse(index) { OPENING_STARTS[index] }
            val local = frame - start
            if (local < 0) continue
            val slotLeft = index * OPENING_PITCH
            val cardScale = spec.track("infinite.open.card.scale", local)
                ?: openingCardScale(local)
            if (cardScale > 0f) {
                val cx = slotLeft + OPENING_PITCH / 2f
                val cy = REFERENCE_HEIGHT / 2f
                canvas.save()
                canvas.scale(cardScale, cardScale, cx, cy)
                drawCard(
                    canvas = canvas,
                    project = project,
                    card = project.cards[index],
                    slotX = slotLeft,
                    spec = spec,
                    revealContent = openingContentProgress(spec, local),
                    pitch = OPENING_PITCH,
                )
                canvas.restore()
            }

            val badgeLocal = local - 5
            if (badgeLocal >= 0) {
                val scale = spec.track("infinite.open.badge.scale", badgeLocal)
                    ?: openingBadgeScale(badgeLocal)
                drawBadge(canvas, project, project.cards[index], slotLeft, spec, scale, badgeLocal)
            }
            val wipe = openingContentProgress(spec, local)
            if (wipe in 0.0001f..0.9999f) drawOpeningWipe(canvas, slotLeft, wipe, OPENING_PITCH)
        }
    }

    private fun openingCardScale(local: Int): Float = sample(
        arrayOf(
            0f to 0.029514f, 1f to 0.203125f, 2f to 0.382639f, 3f to 0.553125f,
            4f to 0.714583f, 5f to 0.863194f, 6f to 0.992361f, 7f to 1f,
        ),
        local.toFloat(),
    ).coerceIn(0f, 1f)

    private fun openingContentProgress(spec: RendererSpec, local: Int): Float {
        spec.track("infinite.open.content.wipe", local)?.let { return it.coerceIn(0f, 1f) }
        return when {
            local < 31 -> 0f
            local >= 59 -> 1f
            else -> ((local - 31) / 28f).coerceIn(0f, 1f)
        }
    }

    private fun openingBadgeScale(local: Int): Float = sample(
        arrayOf(
            0f to 0.020460f, 1f to 0.212275f, 2f to 0.390203f, 3f to 0.568506f,
            4f to 0.732920f, 5f to 0.877799f, 6f to 1.006579f, 7f to 1.119403f,
            8f to 1.194030f, 9f to 1.194030f, 10f to 1.119403f, 11f to 1.014407f,
            12f to 0.946657f, 13f to 0.904092f, 14f to 0.931494f, 15f to 0.973329f,
            16f to 1.026671f, 17f to 1.054054f, 18f to 1.041667f, 19f to 1.026671f,
            20f to 1f,
        ),
        local.toFloat(),
    ).coerceAtLeast(0f)

    private fun drawOpeningWipe(canvas: Canvas, slotX: Float, p: Float, pitch: Float) {
        val y = REFERENCE_HEIGHT * p.coerceIn(0f, 1f)
        val half = 58f
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, y - half, 0f, y + half,
            intArrayOf(Color.TRANSPARENT, Color.argb(28, 255, 255, 255), Color.argb(120, 255, 255, 255), Color.argb(28, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(slotX, y - half, slotX + pitch, y + half, paint)
        paint.shader = null
    }
''',
    "source opening motion",
)

text = replace_section(
    text,
    "    private fun drawConveyor(",
    "    private fun drawCard(",
    '''    private fun drawConveyor(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        val scroll = conveyorScroll(spec, frame)
        val first = floor(scroll / CONVEYOR_PITCH).toInt().coerceAtLeast(0)
        val last = min(project.cards.lastIndex, first + 4)
        for (index in first..last) {
            if (index !in project.cards.indices) continue
            val x = index * CONVEYOR_PITCH - scroll
            if (x >= REFERENCE_WIDTH || x + CONVEYOR_PITCH <= 0f) continue
            val openingLocal = if (index < 4) frame - spec.openingStarts.getOrElse(index) { OPENING_STARTS[index] } else Int.MAX_VALUE
            val reveal = if (index < 4) openingContentProgress(spec, openingLocal) else 1f
            drawCard(canvas, project, project.cards[index], x, spec, reveal, CONVEYOR_PITCH)
            if (index < 4) {
                drawBadge(canvas, project, project.cards[index], x, spec, 1f, Int.MAX_VALUE)
                if (reveal in 0.0001f..0.9999f) drawOpeningWipe(canvas, x, reveal, CONVEYOR_PITCH)
            } else {
                drawLaterBadge(canvas, project, project.cards[index], x, spec, frame, index)
            }
        }
    }

    private fun conveyorScroll(spec: RendererSpec, frame: Int): Float {
        spec.track("infinite.scroll", frame)?.let { return it }
        val normal = (frame - spec.continuousStartFrame).coerceAtLeast(0) * SOURCE_SCROLL_PER_FRAME
        if (frame <= FAST_SCROLL_START) return normal
        val atFastStart = (FAST_SCROLL_START - spec.continuousStartFrame).coerceAtLeast(0) * SOURCE_SCROLL_PER_FRAME
        return atFastStart + (frame - FAST_SCROLL_START) * FAST_SCROLL_PER_FRAME
    }
''',
    "continuous source motion",
)

text = replace_section(
    text,
    "    private fun drawCard(",
    "    private fun drawTitle(",
    '''    private fun drawCard(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        slotX: Float,
        spec: RendererSpec,
        revealContent: Float,
        pitch: Float = CONVEYOR_PITCH,
    ) {
        val right = slotX + CARD_WIDTH
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.alpha = 255
        paint.maskFilter = null
        paint.color = Color.rgb(30, 30, 30)
        canvas.drawRect(slotX, 0f, right, TITLE_TOP, paint)

        paint.color = spec.titleBackgroundColor
        canvas.drawRect(slotX, TITLE_TOP, right, TITLE_BOTTOM, paint)
        paint.color = Color.rgb(226, 128, 0)
        canvas.drawRect(slotX, TITLE_BOTTOM - 6f, right, TITLE_BOTTOM, paint)

        paint.color = spec.descriptionBackgroundColor
        canvas.drawRect(slotX, TITLE_BOTTOM, right, ART_TOP, paint)
        canvas.drawRect(slotX, ART_TOP, right, REFERENCE_HEIGHT.toFloat(), paint)
        paint.color = Color.rgb(8, 8, 8)
        canvas.drawRect(right, 0f, slotX + pitch, REFERENCE_HEIGHT.toFloat(), paint)

        val p = revealContent.coerceIn(0f, 1f)
        if (p <= 0f) return
        canvas.save()
        if (p < 1f) canvas.clipRect(slotX, 0f, slotX + pitch, REFERENCE_HEIGHT * p)
        drawTitle(canvas, project, card, RectF(slotX + 9f, TITLE_TOP + 2f, right - 9f, TITLE_BOTTOM - 8f))
        drawDescription(canvas, project, card, RectF(slotX + 12f, TITLE_BOTTOM + 8f, right - 12f, ART_TOP - 5f))
        drawArtwork(canvas, card, RectF(slotX + 9f, ART_TOP + 3f, right - 9f, REFERENCE_HEIGHT - 3f))
        canvas.restore()
    }
''',
    "vertical content reveal",
)

text = replace_section(
    text,
    "    private fun drawBadge(",
    "    private fun drawBadgeShine(",
    '''    private fun badgePath(slotX: Float): Path = Path().apply {
        moveTo(slotX + 249f, 12f)
        lineTo(slotX + 417f, 96f)
        lineTo(slotX + 459f, 291f)
        lineTo(slotX + 339f, 441f)
        lineTo(slotX + 132f, 441f)
        lineTo(slotX + 18f, 297f)
        lineTo(slotX + 63f, 90f)
        close()
    }

    private fun drawBadge(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        slotX: Float,
        spec: RendererSpec,
        scale: Float,
        animationLocal: Int,
    ) {
        if (!project.showBadges || (card.value.isBlank() && card.badgeHeader.isBlank())) return
        val cx = slotX + 240f
        val cy = 240f
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        val badge = badgePath(slotX)

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.maskFilter = null
        paint.alpha = 255
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.save(); canvas.translate(5f, 8f); canvas.drawPath(badge, paint); canvas.restore()
        paint.shader = LinearGradient(
            0f, 10f, 0f, 445f,
            Color.rgb(239, 29, 23), Color.rgb(204, 11, 11), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(badge, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.rgb(194, 194, 184)
        canvas.drawPath(badge, paint)
        paint.style = Paint.Style.FILL

        if (animationLocal == Int.MAX_VALUE) {
            drawBadgeText(canvas, project, card, cx, spec)
        } else {
            val reveal = openingContentProgress(spec, animationLocal + 5)
            if (reveal > 0f) {
                canvas.save()
                canvas.clipRect(slotX, 0f, slotX + OPENING_PITCH, REFERENCE_HEIGHT * reveal)
                drawBadgeText(canvas, project, card, cx, spec)
                canvas.restore()
            }
        }
        canvas.restore()
    }

    private fun drawLaterBadge(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        slotX: Float,
        spec: RendererSpec,
        frame: Int,
        index: Int,
    ) {
        if (!project.showBadges || (card.value.isBlank() && card.badgeHeader.isBlank())) return
        val firstStart = (spec.track("infinite.later.firstStart", 0) ?: 602f)
        val step = (spec.track("infinite.later.step", 0) ?: 150.62752f)
        val start = kotlin.math.floor(firstStart + (index - 4).coerceAtLeast(0) * step).toInt()
        val local = frame - start
        if (local < 0) return
        val outlineFrames = (spec.track("infinite.later.outlineFrames", 0) ?: 20f).coerceAtLeast(1f)
        val fillStart = (spec.track("infinite.later.fillStart", 0) ?: 23f).coerceAtLeast(outlineFrames)
        val settle = (spec.track("infinite.later.settleFrames", 0) ?: 34f).coerceAtLeast(fillStart + 1f)
        val badge = badgePath(slotX)

        if (local < fillStart) {
            val progress = (local / outlineFrames).coerceIn(0f, 1f)
            val measure = PathMeasure(badge, true)
            val partial = Path()
            measure.getSegment(0f, measure.length * progress, partial, true)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 3f
            paint.shader = null
            paint.maskFilter = null
            paint.alpha = 255
            paint.color = Color.rgb(160, 176, 151)
            canvas.drawPath(partial, paint)
            paint.style = Paint.Style.FILL
            return
        }

        val p = ((local - fillStart) / (settle - fillStart)).coerceIn(0f, 1f)
        val blur = (34f * (1f - p)).coerceAtLeast(0f)
        val alpha = (72f + 183f * p).roundToInt().coerceIn(0, 255)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.alpha = alpha
        paint.maskFilter = if (blur > 0.5f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
        paint.color = spec.badgeColor
        canvas.drawPath(badge, paint)
        paint.maskFilter = null
        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.rgb(194, 194, 184)
        canvas.drawPath(badge, paint)
        paint.style = Paint.Style.FILL
        if (p > 0.08f) {
            canvas.saveLayerAlpha(null, (255f * p).roundToInt().coerceIn(0, 255))
            drawBadgeText(canvas, project, card, slotX + 240f, spec)
            canvas.restore()
        }
    }
''',
    "later badge source animation",
)

text = replace_section(
    text,
    "    private fun drawOutro(",
    "    private fun drawTypedParagraph(",
    '''    private fun drawOutro(canvas: Canvas, project: StudioProject, frame: Int, outroStart: Int, spec: RendererSpec) {
        val last = project.cards.last()
        val sourceX = spec.track("infinite.outro.card.x", frame)?.div(3f) ?: sample(
            arrayOf(
                5305f to 4f, 5310f to 100f, 5315f to 240f, 5320f to 318f,
                5325f to 262f, 5330f to 206f, 5335f to 224f, 5340f to 260f,
                5345f to 268f, 5350f to 264f, 5355f to 266f, 5360f to 268f,
                5370f to 266f,
            ),
            frame.toFloat(),
        )
        val x = sourceX * 3f
        drawCard(canvas, project, last, x, spec, 1f, CONVEYOR_PITCH)
        drawBadge(canvas, project, last, x, spec, 1f, Int.MAX_VALUE)

        val message = "Although it seems impossible there are quite a few people who have swallowed their phone."
        val messageStart = (spec.track("infinite.outro.message.start", 0) ?: 5310f).roundToInt()
        val messageChars = (frame - messageStart).coerceIn(0, message.length)
        if (messageChars > 0) {
            textPaint.typeface = regular
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.rgb(242, 242, 242)
            textPaint.textSize = 45f
            drawTypedParagraph(canvas, message.take(messageChars), RectF(24f, 240f, 750f, 585f), 1.08f)
        }

        val subscribe = "Subscribe"
        val rest = " for more\ncomparison videos."
        val subscribeStart = (spec.track("infinite.outro.subscribe.start", 0) ?: 5407f).roundToInt()
        if (frame >= subscribeStart) {
            val visible = (frame - subscribeStart).coerceAtLeast(0)
            textPaint.typeface = regular
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 48f
            val subVisible = subscribe.take(min(subscribe.length, visible))
            textPaint.color = Color.rgb(224, 132, 15)
            canvas.drawText(subVisible, 24f, 780f, textPaint)
            if (visible > subscribe.length) {
                val restVisible = rest.take((visible - subscribe.length).coerceAtMost(rest.length))
                textPaint.color = Color.rgb(242, 242, 242)
                val first = restVisible.substringBefore('\n')
                canvas.drawText(first, 24f + textPaint.measureText(subscribe), 780f, textPaint)
                if ('\n' in restVisible) canvas.drawText(restVisible.substringAfter('\n'), 24f, 837f, textPaint)
            }
        }
    }
''',
    "source outro motion",
)

text = replace_section(
    text,
    "    companion object {",
    "object InfiniteTimeline {",
    '''    companion object {
        const val SOURCE_EXACT_V2 = true
        const val REFERENCE_WIDTH = 1920
        const val REFERENCE_HEIGHT = 1080
        private const val OPENING_PITCH = 480f
        private const val CONVEYOR_PITCH = 483f
        private const val CARD_WIDTH = 474f
        private const val TITLE_TOP = 471f
        private const val TITLE_BOTTOM = 594f
        private const val ART_TOP = 732f
        private const val SOURCE_SCROLL_PER_FRAME = 3.2065854f
        private const val FAST_SCROLL_PER_FRAME = 24f
        private const val FAST_SCROLL_START = 5265
        private val OPENING_STARTS = intArrayOf(187, 261, 329, 398)
        private val CONTINUOUS_BACKGROUND = Color.rgb(25, 14, 17)
    }
}
''',
    "source constants",
)

# Replace the whole timeline policy object so canonical source timing remains 5457 frames.
start = text.find("object InfiniteTimeline {")
if start < 0:
    raise SystemExit("timeline object marker not found")
text = text[:start] + '''object InfiniteTimeline {
    private const val CANONICAL_CARDS = 35
    private const val CANONICAL_FRAMES = 5457
    private const val CANONICAL_OUTRO_START = 5305
    private const val TAIL_AFTER_LAST_ALIGNMENT = 205
    private const val OUTRO_FRAMES = CANONICAL_FRAMES - CANONICAL_OUTRO_START

    fun isInfinite(spec: RendererSpec): Boolean =
        spec.engine == "infinite-timeline-exact" || spec.id.startsWith("infinite.")

    fun stepFrames(project: StudioProject, spec: RendererSpec): Float {
        if (project.autoLength) return spec.continuousStepFrames.coerceAtLeast(1).toFloat()
        if (project.cards.size <= 4) return spec.continuousStepFrames.coerceAtLeast(1).toFloat()
        val targetFrames = (project.customLengthSeconds * project.fps.coerceAtLeast(1)).roundToInt()
        val fixed = spec.continuousStartFrame + TAIL_AFTER_LAST_ALIGNMENT + OUTRO_FRAMES
        val moving = (targetFrames - fixed).coerceAtLeast(project.cards.size - 4)
        return moving.toFloat() / (project.cards.size - 4).coerceAtLeast(1)
    }

    fun outroStartFrame(project: StudioProject, spec: RendererSpec): Int {
        if (project.autoLength && project.cards.size == CANONICAL_CARDS && spec.canonicalFrameCount == CANONICAL_FRAMES) {
            return CANONICAL_OUTRO_START
        }
        val steps = (project.cards.size - 4).coerceAtLeast(0)
        return spec.continuousStartFrame + (steps * stepFrames(project, spec)).roundToInt() + TAIL_AFTER_LAST_ALIGNMENT
    }

    fun totalFrameCount(project: StudioProject, spec: RendererSpec): Int {
        if (project.autoLength && project.cards.size == CANONICAL_CARDS && spec.canonicalFrameCount == CANONICAL_FRAMES) {
            return CANONICAL_FRAMES
        }
        return outroStartFrame(project, spec) + OUTRO_FRAMES
    }
}
'''

TARGET.write_text(text)
print("Applied Infinite Comparison source-exact v2 animation reconstruction")
