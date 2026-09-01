#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


# Renderer capabilities: these effects are declarative tracks. An older app
# must reject the bundle instead of silently falling back to generic text/shine.
path = ANDROID / "RendererBundle.kt"
text = path.read_text()
for feature in ("ribbon-text-wipe-v1", "ribbon-glass-shine-v1"):
    if f'"{feature}"' not in text:
        marker = '        "ribbon-artwork-region-v1",\n'
        if marker not in text:
            marker = '        "artwork-transform",\n'
        if marker not in text:
            raise SystemExit("renderer feature insertion marker changed")
        text = text.replace(marker, marker + f'        "{feature}",\n', 1)
path.write_text(text)


path = ANDROID / "RibbonFrameRenderer.kt"
text = path.read_text()

# Source letter reveal: when a renderer supplies text.N.reveal, draw the sharp
# part only up to that fraction while a blurred ghost extends ahead of it. This
# reproduces the Puberty reference's YEARS OLD wipe instead of revealing the
# whole line at once.
needle = '''            while (textPaint.measureText(text) > 264f && size > 18f) {
                size -= 2f
                textPaint.textSize = size
            }

            if (progress < 0.92f) {
'''
replacement = '''            while (textPaint.measureText(text) > 264f && size > 18f) {
                size -= 2f
                textPaint.textSize = size
            }

            val exactReveal = motionTrack(spec, "$prefix.text.$index.reveal", local)
                ?: motionTrack(spec, "$prefix.text.reveal", local)
            if (exactReveal != null) {
                val leadFraction = motionTrack(spec, "$prefix.text.$index.lead", local)
                    ?: motionTrack(spec, "$prefix.text.lead", local)
                    ?: 0.18f
                val leadBlur = motionTrack(spec, "$prefix.text.$index.lead.blur", local)
                    ?: motionTrack(spec, "$prefix.text.lead.blur", local)
                    ?: 10f
                val leadAlpha = motionTrack(spec, "$prefix.text.$index.lead.alpha", local)
                    ?: motionTrack(spec, "$prefix.text.lead.alpha", local)
                    ?: 0.55f
                drawRendererTextReveal(
                    canvas = canvas,
                    text = text,
                    centerX = spec.badgeCenterX,
                    baselineY = y,
                    alpha = alpha,
                    reveal = exactReveal,
                    leadFraction = leadFraction,
                    leadBlur = leadBlur,
                    leadAlpha = leadAlpha,
                    sharpBlur = exactBlur ?: 0f,
                )
                return@forEachIndexed
            }

            if (progress < 0.92f) {
'''
text = replace_once(text, needle, replacement, "source text reveal insertion")

helper_marker = '''    private fun textLandingOffset(age: Float): Float = when {'''
helper = '''    private fun drawRendererTextReveal(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        alpha: Int,
        reveal: Float,
        leadFraction: Float,
        leadBlur: Float,
        leadAlpha: Float,
        sharpBlur: Float,
    ) {
        val width = textPaint.measureText(text).coerceAtLeast(1f)
        val left = centerX - width / 2f
        val right = centerX + width / 2f
        val sharpX = lerp(left, right, reveal.coerceIn(0f, 1f))
        val leadX = (sharpX + width * leadFraction.coerceIn(0f, 0.55f)).coerceAtMost(right + 12f)
        val top = baselineY + textPaint.ascent() - 28f
        val bottom = baselineY + textPaint.descent() + 28f

        // Soft preview of the upcoming glyphs: the reference exposes a hazy
        // leading edge before each letter becomes crisp.
        if (leadX > sharpX + 0.5f && alpha > 0) {
            canvas.save()
            canvas.clipRect(sharpX - 8f, top, leadX, bottom)
            textPaint.color = Color.argb(
                (alpha * leadAlpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255),
                255, 255, 255,
            )
            textPaint.maskFilter = BlurMaskFilter(leadBlur.coerceAtLeast(0.2f), BlurMaskFilter.Blur.NORMAL)
            drawCenteredBaselineText(canvas, text, centerX, baselineY, textPaint)
            canvas.restore()
        }

        if (sharpX <= left || alpha <= 0) {
            textPaint.maskFilter = null
            return
        }

        canvas.save()
        canvas.clipRect(left - 18f, top, sharpX, bottom)
        textPaint.maskFilter = if (sharpBlur > 0.05f) {
            BlurMaskFilter(sharpBlur, BlurMaskFilter.Blur.NORMAL)
        } else null
        textPaint.color = Color.argb((alpha * 0.42f).roundToInt().coerceIn(0, 255), 20, 20, 20)
        drawCenteredBaselineText(canvas, text, centerX + 3f, baselineY + 5f, textPaint)
        textPaint.color = Color.argb(alpha.coerceIn(0, 255), 255, 255, 255)
        drawCenteredBaselineText(canvas, text, centerX, baselineY, textPaint)
        canvas.restore()
        textPaint.maskFilter = null
    }

'''
if helper not in text:
    if text.count(helper_marker) != 1:
        raise SystemExit("source text helper insertion marker changed")
    text = text.replace(helper_marker, helper + helper_marker, 1)

# Full-visibility glass shine. The renderer activates this by providing
# shine.glass.opacity. The narrow core reaches 255 alpha; broad and middle bands
# are feathered to preserve the translucent glass look visible in the reference.
shine_needle = '''        val topX = lerp(130f, 420f, progress)
        val bottomX = topX - 205f
        // Broad, translucent sweep: thicker than before without becoming a white streak.
        val broadHalfWidth = 40f
'''
shine_replacement = '''        val topX = lerp(130f, 420f, progress)
        val bottomX = topX - 205f
        val glassOpacity = if (index < 4) {
            motionTrack(spec, "ribbon.open.$index.shine.glass.opacity", local)
        } else {
            motionTrack(spec, "ribbon.card.$index.shine.glass.opacity", local)
                ?: motionTrack(spec, "ribbon.later.shine.glass.opacity", local)
        }
        if (glassOpacity != null && glassOpacity > 0.001f) {
            val glassWidth = if (index < 4) {
                motionTrack(spec, "ribbon.open.$index.shine.glass.width", local)
            } else {
                motionTrack(spec, "ribbon.card.$index.shine.glass.width", local)
                    ?: motionTrack(spec, "ribbon.later.shine.glass.width", local)
            } ?: 94f
            drawRendererGlassShine(
                canvas,
                badge,
                topX,
                bottomX,
                (alpha * glassOpacity).coerceIn(0f, 1f),
                glassWidth,
            )
            return
        }
        // Broad, translucent sweep: thicker than before without becoming a white streak.
        val broadHalfWidth = 40f
'''
text = replace_once(text, shine_needle, shine_replacement, "glass shine dispatch")

shine_helper_marker = '''    private fun drawSimpleMultiline('''
shine_helper = '''    private fun drawRendererGlassShine(
        canvas: Canvas,
        badge: Path,
        topX: Float,
        bottomX: Float,
        alpha: Float,
        width: Float,
    ) {
        if (alpha <= 0.001f) return
        fun drawBand(halfWidth: Float, opacity: Float, blur: Float) {
            shineBroadPath.reset()
            shineBroadPath.moveTo(topX - halfWidth, -80f)
            shineBroadPath.lineTo(topX + halfWidth, -80f)
            shineBroadPath.lineTo(bottomX + halfWidth, 500f)
            shineBroadPath.lineTo(bottomX - halfWidth, 500f)
            shineBroadPath.close()
            paint.color = Color.argb(
                (255f * alpha * opacity).roundToInt().coerceIn(0, 255),
                255, 255, 255,
            )
            paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(shineBroadPath, paint)
        }

        canvas.save()
        canvas.clipPath(badge)
        val half = width.coerceIn(36f, 150f) / 2f
        drawBand(half, 0.32f, 10.5f)
        drawBand(half * 0.48f, 0.62f, 5.0f)
        // 100% opaque centre at peak: visible glass highlight rather than the
        // previous 60/255 maximum-alpha streak.
        drawBand(max(5.5f, half * 0.13f), 1.0f, 1.6f)
        paint.maskFilter = null
        canvas.restore()
    }

'''
if shine_helper not in text:
    if text.count(shine_helper_marker) != 1:
        raise SystemExit("glass shine helper insertion marker changed")
    text = text.replace(shine_helper_marker, shine_helper + shine_helper_marker, 1)
path.write_text(text)

print("Added renderer-owned source text wipe and full-visibility glass shine")
