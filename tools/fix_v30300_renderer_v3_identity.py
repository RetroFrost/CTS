#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
text = path.read_text()
old = '    val name: String = "Cubical Compare 2.0.8 Native",\n'
new = '    val name: String = "Cubical Compare 3.0.300 Native",\n'
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("Built-in renderer identity marker changed")

required = (
    'const val RENDERER_API = 3',
    '"scene-v3"',
    '"animated-rect-clip"',
    '"raw-frame-tracks"',
    '"source-baked-text-raster"',
    '"independent-shadow-resource"',
    '"frame-addressed-selectors"',
    '"exact-outro-overlay"',
    '"preview-export-identical-path"',
)
missing = [needle for needle in required if needle not in text]
if missing:
    raise SystemExit(f"Renderer v3 capability contract incomplete: {missing}")

path.write_text(text)
print("Cubical Compare 3.0.300 Renderer v3 identity/capabilities verified")
