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
    path = ROOT / "tools" / "apply_renderer_v3_low_memory.py"
    text = path.read_text()
    old = '''    count = text.count(old)\n    if count != 1:\n        raise SystemExit(f"{label}: expected exactly one source match, found {count}")\n    return text.replace(old, new, 1)\n'''
    new = '''    count = text.count(old)\n    if count > 1 and label.startswith("v3 validation "):\n        print(f"{label}: applying to {count} validation overloads")\n        return text.replace(old, new)\n    if count != 1:\n        raise SystemExit(f"{label}: expected exactly one source match, found {count}")\n    return text.replace(old, new, 1)\n'''
    if new not in text:
        if old not in text:
            raise SystemExit("low-memory patch helper marker changed")
        text = text.replace(old, new, 1)

    old_anchor = '''helper_anchor = ''' + "'''    private fun readLimited(input: InputStream, limit: Int): ByteArray {\n'''" + '''
helper = '''
    new_anchor = '''helper_anchor = ''' + "'''    private fun readLimited(input: InputStream, limit: Int): ByteArray {\n'''" + '''
if helper_anchor not in text:
    helper_anchor = ''' + "'''    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {\n'''" + '''
helper = '''
    if new_anchor not in text:
        if old_anchor not in text:
            raise SystemExit("low-memory readLimited anchor declaration changed")
        text = text.replace(old_anchor, new_anchor, 1)
    path.write_text(text)


def run_feature_migrations() -> None:
    v3 = (ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererV3.kt").read_text()
    v3r = (ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererV3FrameRenderer.kt").read_text()
    feature_impl_present = all(marker in v3 for marker in (
        "private fun validateDedicatedFeatureContracts(",
        "private fun containsRawFrameTrack(",
    )) and all(marker in v3r for marker in (
        "private fun drawSourceBakedTextRaster(",
        "private fun drawIndependentShadow(",
        "private fun drawExactOutroOverlay(",
    ))
    if feature_impl_present:
        print("Renderer v3 dedicated feature contracts already baked into source; not replaying migration")
    else:
        subprocess.run([sys.executable, str(ROOT / "tools/apply_renderer_v3_feature_contracts.py")], check=True)

    subprocess.run([sys.executable, str(ROOT / "tools/apply_renderer_v3_feature_compat.py")], check=True)

    test_path = ROOT / "android/app/src/androidTest/java/io/github/retrofrost/cts/android/RendererV3InstrumentedTest.kt"
    test_text = test_path.read_text() if test_path.is_file() else ""
    if "dedicatedRendererV3FeaturesAreRealAndPreviewExportPixelsMatch" in test_text:
        print("Renderer v3 dedicated feature pixel tests already baked into source")
    else:
        subprocess.run([sys.executable, str(ROOT / "tools/apply_renderer_v3_feature_tests.py")], check=True)

    subprocess.run([sys.executable, str(ROOT / "tools/apply_renderer_v3_low_memory.py")], check=True)


text = PATH.read_text()
field = '    private val relationshipsPrecisionRenderer = bridgeField("relationshipsPrecisionRenderer")\n'
text = replace_once(text, field, field + '    private val rendererV3 = bridgeField("rendererV3") as RendererV3FrameRenderer\n', "v3 direct-export renderer field")
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
run_feature_migrations()
print("Applied Renderer API v3 direct GPU export dispatch + real dedicated feature contracts + low-memory package runtime")
