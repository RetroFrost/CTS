#!/usr/bin/env python3
"""Keep Renderer API v3 preview and direct GPU export on the same scene evaluator."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/DirectGpuVideoExporter.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


def prepare_low_memory_patch() -> None:
    """Adapt the strict new OOM patch to the two v3 validation overloads.

    Feature-contract integration intentionally introduced two validation paths with
    the same asset-list signature. Both must gain the disk-backed asset index. This
    keeps the low-memory patch strict everywhere else instead of globally ignoring
    changed anchors.
    """
    path = ROOT / "tools" / "apply_renderer_v3_low_memory.py"
    text = path.read_text()
    old = '''    count = text.count(old)\n    if count != 1:\n        raise SystemExit(f"{label}: expected exactly one source match, found {count}")\n    return text.replace(old, new, 1)\n'''
    new = '''    count = text.count(old)\n    if count > 1 and label.startswith("v3 validation "):\n        print(f"{label}: applying to {count} validation overloads")\n        return text.replace(old, new)\n    if count != 1:\n        raise SystemExit(f"{label}: expected exactly one source match, found {count}")\n    return text.replace(old, new, 1)\n'''
    if new not in text:
        if old not in text:
            raise SystemExit("low-memory patch helper marker changed")
        path.write_text(text.replace(old, new, 1))


text = PATH.read_text()

field = '    private val relationshipsPrecisionRenderer = bridgeField("relationshipsPrecisionRenderer")\n'
text = replace_once(
    text,
    field,
    field + '    private val rendererV3 = bridgeField("rendererV3") as RendererV3FrameRenderer\n',
    "v3 direct-export renderer field",
)

# All engines are reference-space renderers; use the manifest reference dimensions
# instead of permanently assuming 1920x1080. Existing engines still declare 1920x1080.
text = replace_once(
    text,
    '''            canvas.scale(
                outputWidth.coerceAtLeast(2) / 1920f,
                outputHeight.coerceAtLeast(2) / 1080f,
            )
''',
    '''            canvas.scale(
                outputWidth.coerceAtLeast(2) / spec.referenceWidth.coerceAtLeast(1).toFloat(),
                outputHeight.coerceAtLeast(2) / spec.referenceHeight.coerceAtLeast(1).toFloat(),
            )
''',
    "manifest reference-space export scale",
)

# runtime-integrity-v4 has already made this a manifest-owned when(engineKind).
anchor = '''                "ribbon-exact" ->
                    drawFourArg(ribbonRenderer, canvas, project, engineFrame, spec)
                else ->
                    drawFourArg(nativeRenderer, canvas, project, engineFrame, spec)
'''
replacement = '''                "ribbon-exact" ->
                    drawFourArg(ribbonRenderer, canvas, project, engineFrame, spec)
                "scene-v3" ->
                    drawRendererV3(canvas, project, engineFrame, spec)
                else ->
                    drawFourArg(nativeRenderer, canvas, project, engineFrame, spec)
'''
text = replace_once(text, anchor, replacement, "v3 direct GPU dispatch")

helper_anchor = '''    private fun drawFourArg(
        renderer: Any,
'''
helper = '''    private fun drawRendererV3(
        canvas: Canvas,
        project: StudioProject,
        frame: Int,
        spec: RendererSpec,
    ) {
        // RendererV3FrameRenderer is the single scene evaluator. The direct Surface
        // path composites its exact reference raster into the already-scaled encoder
        // Canvas, avoiding a second implementation with different selector semantics.
        val bitmap = rendererV3.render(
            project = project,
            spec = spec,
            frame = frame,
            width = spec.referenceWidth.coerceAtLeast(2),
            height = spec.referenceHeight.coerceAtLeast(2),
        )
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            bitmap.recycle()
        }
    }

'''
if helper not in text:
    if text.count(helper_anchor) != 1:
        raise SystemExit("v3 direct-export helper anchor changed")
    text = text.replace(helper_anchor, helper + helper_anchor, 1)

PATH.write_text(text)
prepare_low_memory_patch()

# This is deliberately last in the v3 patch chain: project binding/group/homography
# and direct-export dispatch must exist before the dedicated feature implementations
# are patched and tested.
for script in (
    "apply_renderer_v3_feature_contracts.py",
    "apply_renderer_v3_feature_compat.py",
    "apply_renderer_v3_feature_tests.py",
    "apply_renderer_v3_low_memory.py",
):
    subprocess.run([sys.executable, str(ROOT / "tools" / script)], check=True)

print("Applied Renderer API v3 direct GPU export dispatch + real dedicated feature contracts + low-memory package runtime")
