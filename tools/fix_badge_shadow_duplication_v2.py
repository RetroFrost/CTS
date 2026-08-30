from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"marker not found in {path}: {old[:220]!r}")
    p.write_text(text.replace(old, new, 1))

renderer = "android/app/src/main/java/io/github/retrofrost/cts/android/RelationshipsPrecisionFrameRenderer.kt"
old_shadow = '''        val shadowColor = cfg.color("badge.shadow.color", Color.TRANSPARENT)
        val shadowRadius = cfg.float("badge.shadow.radius", 0f)
        if (Color.alpha(shadowColor) > 0 && shadowRadius > 0f) {
            // Shadow-only pass: never repaint the badge fill as part of a shadow.
            paint.resetForShape()
            paint.style = Paint.Style.FILL
            paint.color = shadowColor
            paint.maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL)
            canvas.save()
            canvas.translate(cfg.float("badge.shadow.dx", 0f), cfg.float("badge.shadow.dy", 0f))
            canvas.drawPath(path, paint)
            canvas.restore()
            paint.maskFilter = null
        }
'''
new_shadow = '''        val shadowColor = cfg.color("badge.shadow.color", Color.TRANSPARENT)
        val shadowRadius = cfg.float("badge.shadow.radius", 0f).coerceAtLeast(0f)
        val shadowDx = cfg.float("badge.shadow.dx", 0f)
        val shadowDy = cfg.float("badge.shadow.dy", 0f)
        if (Color.alpha(shadowColor) > 0 && (shadowRadius > 0f || shadowDx != 0f || shadowDy != 0f)) {
            drawBadgeShadow(canvas, path, shadowColor, shadowRadius, shadowDx, shadowDy)
        }
'''
replace_once(renderer, old_shadow, new_shadow)

badge_path_end = '''        return Path().apply {
            pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.first, p.second) else lineTo(p.first, p.second) }
            close()
        }
    }

    private fun drawBadgeShine'''
helper = '''        return Path().apply {
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

    private fun drawBadgeShine'''
replace_once(renderer, badge_path_end, helper)

bundle = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
replace_once(
    bundle,
    '''        "relationships-shadow-mask-v1",
        "relationships-single-owner-pass-v1",''',
    '''        "relationships-shadow-mask-v1",
        "relationships-shadow-outside-v2",
        "relationships-single-owner-pass-v1",''',
)

test = Path("android/app/src/test/java/io/github/retrofrost/cts/android/RelationshipsPrecisionRendererTest.kt")
text = test.read_text()
old = '''    fun rendererAdvertisesGlobalAntiDuplicationCapabilities() {
        assertTrue(RendererCapabilities.features.contains("relationships-shadow-mask-v1"))
        assertTrue(RendererCapabilities.features.contains("relationships-single-owner-pass-v1"))
    }'''
new = '''    fun rendererAdvertisesGlobalAntiDuplicationCapabilities() {
        assertTrue(RendererCapabilities.features.contains("relationships-shadow-mask-v1"))
        assertTrue(RendererCapabilities.features.contains("relationships-shadow-outside-v2"))
        assertTrue(RendererCapabilities.features.contains("relationships-single-owner-pass-v1"))
    }'''
if old not in text:
    raise SystemExit("anti-duplication capability test marker not found")
test.write_text(text.replace(old, new, 1))

doc = Path("docs/wiki/Relationships-Exact-v2.md")
if doc.exists():
    text = doc.read_text()
    old_doc = '''Exact-v2 builds advertise both `relationships-shadow-mask-v1` and `relationships-single-owner-pass-v1`. Every logical frame element has one owning paint pass: intro, footer, content, each card body, each badge, front artwork, disclaimer, and outro. Intentional layers inside a single element (for example a gradient plus stroke plus shine) remain part of that one owner. Badge shadows use a blur-mask-only pass so the source badge fill is never painted twice. Preview and export both execute the same precision renderer, so the invariant applies to both paths.'''
    new_doc = '''Exact-v2 builds advertise `relationships-shadow-mask-v1`, `relationships-shadow-outside-v2` and `relationships-single-owner-pass-v1`. Every logical frame element has one owning paint pass: intro, footer, content, each card body, each badge, front artwork, disclaimer, and outro. Intentional layers inside a single element (for example a gradient plus stroke plus shine) remain part of that one owner. Badge shadows never paint a second complete badge silhouette: the cast-shadow body is path-differenced against the real badge and its blur is outer-only, while the actual badge polygon is filled exactly once. Preview and export both execute the same precision renderer, so the invariant applies to both paths.'''
    if old_doc not in text:
        raise SystemExit("anti-duplication doc marker not found")
    doc.write_text(text.replace(old_doc, new_doc, 1))
