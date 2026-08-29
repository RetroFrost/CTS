from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"marker not found in {path}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1))

renderer = "android/app/src/main/java/io/github/retrofrost/cts/android/RelationshipsPrecisionFrameRenderer.kt"
replace_once(
    renderer,
    "import android.graphics.BitmapFactory\n",
    "import android.graphics.BitmapFactory\nimport android.graphics.BlurMaskFilter\n",
)

replace_once(
    renderer,
    '''        val shadowColor = cfg.color("badge.shadow.color", Color.TRANSPARENT)
        val shadowRadius = cfg.float("badge.shadow.radius", 0f)
        paint.resetForShape()
        if (Color.alpha(shadowColor) > 0 && shadowRadius > 0f) {
            paint.color = spec.badgeColor
            paint.setShadowLayer(shadowRadius, cfg.float("badge.shadow.dx", 0f), cfg.float("badge.shadow.dy", 0f), shadowColor)
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()
        }
''',
    '''        val shadowColor = cfg.color("badge.shadow.color", Color.TRANSPARENT)
        val shadowRadius = cfg.float("badge.shadow.radius", 0f)
        if (Color.alpha(shadowColor) > 0 && shadowRadius > 0f) {
            // IMPORTANT: shadow-only pass. Never use Paint.setShadowLayer with a
            // visible badge fill here: that redraws the badge body before the real
            // gradient/strokes and can appear as a second offset badge in preview
            // and export. BlurMaskFilter renders only the declared shadow geometry.
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
''',
)

bundle = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
replace_once(
    bundle,
    '''        "relationships-rich-typography",
        "preview-frames",''',
    '''        "relationships-rich-typography",
        "relationships-shadow-mask-v1",
        "preview-frames",''',
)

test = "android/app/src/test/java/io/github/retrofrost/cts/android/RelationshipsPrecisionRendererTest.kt"
text = Path(test).read_text()
if "relationships-shadow-mask-v1" not in text:
    insert = '''

    @Test
    fun badgeShadowMaskCapabilityIsPermanent() {
        assertTrue(RendererCapabilities.features.contains("relationships-shadow-mask-v1"))
    }
'''
    if not text.rstrip().endswith('}'):
        raise SystemExit('test class closing brace not found')
    text = text.rstrip()[:-1] + insert + '\n}\n'
    Path(test).write_text(text)

doc = Path("docs/wiki/Relationships-Exact-v2.md")
if doc.exists():
    text = doc.read_text()
    note = '''\n### Badge shadow anti-duplication guarantee\n\nExact-v2 builds advertise `relationships-shadow-mask-v1`. Badge shadows are rendered as a blur-mask-only pass; the badge fill/gradient/strokes are rendered exactly once. Bundles that require this capability are rejected by older builds that do not implement the shadow-only path.\n'''
    if "relationships-shadow-mask-v1" not in text:
        doc.write_text(text.rstrip() + note + "\n")
