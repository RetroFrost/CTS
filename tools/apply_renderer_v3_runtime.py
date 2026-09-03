#!/usr/bin/env python3
"""Integrate Renderer API v3 into the Android 3.0.300 runtime.

This patch intentionally runs after the source-measurement/runtime-integrity patches.
It keeps the established v1/v2 code paths intact, then adds .renderer3 / packaged-v3
loading and dispatch through RendererV3FrameRenderer.
"""
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


def insert_into_set(text: str, declaration: str, values: list[str], label: str) -> str:
    start = text.find(declaration)
    if start < 0:
        raise SystemExit(f"{label}: set declaration missing")
    open_paren = text.find("(", start)
    if open_paren < 0:
        raise SystemExit(f"{label}: set opening parenthesis missing")
    cursor = open_paren + 1
    depth = 1
    while cursor < len(text) and depth:
        char = text[cursor]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        cursor += 1
    if depth != 0:
        raise SystemExit(f"{label}: unterminated set")
    close_paren = cursor - 1
    missing = [value for value in values if f'"{value}"' not in text[start:close_paren]]
    if not missing:
        return text
    indent = "        "
    addition = "".join(f'{indent}"{value}",\n' for value in missing)
    return text[:close_paren] + addition + text[close_paren:]


# ---------------------------------------------------------------------------
# RendererBundle / store / capability contract.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBundle.kt"
text = path.read_text()

text = text.replace('    const val RENDERER_API = 2', '    const val RENDERER_API = 3')
text = text.replace('    const val APP_VERSION = "2.0.8"', '    val APP_VERSION: String get() = BuildConfig.VERSION_NAME.substringBefore(\'-\')')

text = insert_into_set(text, "    val engines = setOf(", ["scene-v3"], "renderer v3 engine capability")
text = insert_into_set(
    text,
    "    val features = setOf(",
    [
        "per-frame-polygon-vertices",
        "full-2d-transforms",
        "shine-geometry-tracks",
        "arbitrary-masks",
        "track-interpolation-modes",
        "per-item-animation",
        "exact-text-tracks",
        "source-baked-text-raster",
        "explicit-layer-order",
        "independent-shadow-resources",
        "independent-shadow-resource",
        "dense-frame-data",
        "raw-frame-tracks",
        "frame-addressed-objects",
        "frame-addressed-selectors",
        "property-level-selector-inheritance",
        "deterministic-selector-precedence",
        "zero-implicit-animation",
        "generic-renderer-resources",
        "group-transforms",
        "resource-lifespans",
        "selector-shared-behaviour",
        "renderer-owned-geometry",
        "renderer-owned-materials",
        "blend-compositing-modes",
        "per-frame-filter-tracks",
        "exact-artwork-transforms",
        "absolute-integer-frame-clock",
        "single-scene-preview-export-contract",
        "preview-export-identical-path",
        "reference-resolution-fps-lock",
        "frame-checkpoints",
        "pixel-diff-audit-contract",
        "selector-cascade-inspection",
        "exact-outro-overlay",
        "renderer-api-v3-scene-ir",
        "renderer-v3-sidecar-resources",
        "renderer-v3-zip-package",
    ],
    "renderer v3 feature capabilities",
)

text = text.replace('errorIf(spec.formatVersion !in 1..2, "Unsupported renderer schema ${spec.formatVersion}.")',
                    'errorIf(spec.formatVersion !in 1..3, "Unsupported renderer schema ${spec.formatVersion}.")')
text = text.replace('    const val MAX_FILE_BYTES = 16 * 1024 * 1024', '    const val MAX_FILE_BYTES = 128 * 1024 * 1024')
text = text.replace('    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024', '    private const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024')

# Preserve the mature legacy parser exactly and add a v3 dispatcher in front of it.
legacy_signature = '    fun read(input: InputStream): RendererSpec {\n'
legacy_private = '    private fun readLegacy(input: InputStream): RendererSpec {\n'
wrapper = '''    fun read(input: InputStream): RendererSpec {
        val bytes = input.readBytes()
        require(bytes.size <= RendererV3Bundle.MAX_FILE_BYTES) { "Renderer file is too large." }
        if (RendererV3Bundle.accepts(bytes)) return RendererV3Bundle.read(bytes).spec
        return readLegacy(ByteArrayInputStream(bytes))
    }

'''
if wrapper not in text:
    if legacy_signature not in text:
        raise SystemExit("renderer read dispatcher: legacy read signature missing")
    text = text.replace(legacy_signature, legacy_private, 1)
    marker = legacy_private
    text = text.replace(marker, wrapper + marker, 1)

write_signature = '    fun write(spec: RendererSpec, output: OutputStream) {\n'
v3_write = '''    fun write(spec: RendererSpec, output: OutputStream) {
        if (spec.rendererApi >= 3 || spec.engine == "scene-v3") {
            val scene = requireNotNull(RendererV3Runtime.scene(spec)) {
                "Renderer v3 scene '${spec.id}' is not loaded. Re-import the renderer before exporting it."
            }
            RendererV3Bundle.write(scene, output)
            return
        }
'''
if v3_write not in text:
    if write_signature not in text:
        raise SystemExit("renderer v3 writer: write signature missing")
    text = text.replace(write_signature, v3_write, 1)

path.write_text(text)


# ---------------------------------------------------------------------------
# Shared preview/export dispatch.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBridge.kt"
text = path.read_text()

field_marker = '    private val relationshipsPrecisionRenderer = RelationshipsPrecisionFrameRenderer()\n'
field_value = field_marker + '    private val rendererV3 = RendererV3FrameRenderer()\n'
text = replace_once(text, field_marker, field_value, "renderer v3 bridge field")

if '"scene-v3" -> "scene-v3"' not in text:
    text = replace_once(
        text,
        '        "native-standard" -> "native-standard"\n',
        '        "native-standard" -> "native-standard"\n        "scene-v3" -> "scene-v3"\n',
        "renderer v3 manifest dispatch",
    )

# baseFrameCount has been made manifest-authoritative by runtime-integrity-v4.
if '"scene-v3" -> RendererV3Runtime.scene(spec)?.timeline?.frames' not in text:
    anchor = '            "ribbon-exact" -> RibbonTimeline.totalFrameCount(project, spec)\n'
    addition = anchor + '            "scene-v3" -> RendererV3Runtime.scene(spec)?.timeline?.frames ?: spec.canonicalFrameCount\n'
    text = replace_once(text, anchor, addition, "renderer v3 timeline frame count")

old_intro = '''    fun rendererIntroFrames(spec: RendererSpec = RendererRuntime.active): Int =
        spec.openingStarts.firstOrNull()?.coerceAtLeast(0) ?: 0
'''
new_intro = '''    fun rendererIntroFrames(spec: RendererSpec = RendererRuntime.active): Int =
        if (spec.rendererApi >= 3 || spec.engine == "scene-v3") 0
        else spec.openingStarts.firstOrNull()?.coerceAtLeast(0) ?: 0
'''
text = replace_once(text, old_intro, new_intro, "renderer v3 absolute timeline intro")

bitmap_anchor = '            "ribbon-exact" -> ribbonRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))\n'
bitmap_add = bitmap_anchor + '            "scene-v3" -> rendererV3.render(project, spec, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))\n'
if bitmap_add not in text:
    text = replace_once(text, bitmap_anchor, bitmap_add, "renderer v3 bitmap dispatch")

rgba_anchor = '            "ribbon-exact" -> ribbonRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))\n'
rgba_add = rgba_anchor + '            "scene-v3" -> rendererV3.renderRgba(project, spec, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))\n'
if rgba_add not in text:
    text = replace_once(text, rgba_anchor, rgba_add, "renderer v3 RGBA dispatch")

path.write_text(text)


# ---------------------------------------------------------------------------
# Import UI: raw .renderer3 and packaged-v3 MIME are first-class picker types.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererImportActivity.kt"
text = path.read_text()
text = text.replace(
    'picker.launch(arrayOf(RENDERER_MIME, "application/octet-stream", "*/*"))',
    'picker.launch(arrayOf(RENDERER_MIME, RENDERER_V3_MIME, "application/zip", "application/octet-stream", "*/*"))',
)
text = text.replace(
    '        const val RENDERER_MIME = "application/vnd.cubicalcompare.renderer"\n',
    '        const val RENDERER_MIME = "application/vnd.cubicalcompare.renderer"\n'
    '        const val RENDERER_V3_MIME = "application/vnd.cubicalcompare.renderer3"\n',
)
path.write_text(text)

print("Applied Cubical Compare 3.0.300 Renderer API v3 runtime integration")
