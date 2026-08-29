from pathlib import Path

path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/RelationshipsPrecisionFrameRenderer.kt")
text = path.read_text(encoding="utf-8")
needle = '''    private fun drawIntroLogo(canvas: Canvas, frame: Int, spec: RendererSpec, cfg: ExactConfig) {\n        val fadeIn = smooth((frame / cfg.float("intro.fadeInFrames", 36f)).coerceIn(0f, 1f))\n'''
replacement = '''    private fun drawIntroLogo(canvas: Canvas, frame: Int, spec: RendererSpec, cfg: ExactConfig) {\n        if (!cfg.bool("intro.enabled", false)) return\n        val fadeIn = smooth((frame / cfg.float("intro.fadeInFrames", 36f)).coerceIn(0f, 1f))\n'''
if needle not in text:
    raise SystemExit("drawIntroLogo insertion point not found")
text = text.replace(needle, replacement, 1)
path.write_text(text, encoding="utf-8")
