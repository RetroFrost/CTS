#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
BRIDGE = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBridge.kt"
GPU = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/DirectGpuVideoExporter.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


bundle = BUNDLE.read_text()
bundle = replace_once(
    bundle,
    '        "native-standard",\n        "ribbon-exact",',
    '        "native-standard",\n        "infinite-timeline-exact",\n        "ribbon-exact",',
    "renderer engine capability",
)
bundle = replace_once(
    bundle,
    '        "preview-frames",\n',
    '        "preview-frames",\n        "infinite-timeline-source-v1",\n        "infinite-timeline-source-v2",\n        "source-30fps",\n        "direct-gpu-canvas",\n',
    "renderer feature capability",
)
BUNDLE.write_text(bundle)

bridge = BRIDGE.read_text()
bridge = replace_once(
    bridge,
    '    private val nativeRenderer = NativeFrameRenderer()\n    private val ribbonRenderer = RibbonFrameRenderer()',
    '    private val nativeRenderer = NativeFrameRenderer()\n    private val infiniteRenderer = InfiniteTimelineFrameRenderer()\n    private val ribbonRenderer = RibbonFrameRenderer()',
    "infinite renderer field",
)
bridge = replace_once(
    bridge,
    '    private fun engine(spec: RendererSpec = RendererRuntime.active): String = when {\n        RelationshipsTimeline.isRelationships(spec) -> "relationships-exact"',
    '    private fun engine(spec: RendererSpec = RendererRuntime.active): String = when {\n        InfiniteTimeline.isInfinite(spec) -> "infinite-timeline-exact"\n        RelationshipsTimeline.isRelationships(spec) -> "relationships-exact"',
    "engine dispatch",
)
bridge = replace_once(
    bridge,
    '    private fun baseFrameCount(project: StudioProject, spec: RendererSpec): Int = when (engine(spec)) {\n        "relationships-exact" -> RelationshipsTimeline.totalFrameCount(project, spec)',
    '    private fun baseFrameCount(project: StudioProject, spec: RendererSpec): Int = when (engine(spec)) {\n        "infinite-timeline-exact" -> InfiniteTimeline.totalFrameCount(project, spec)\n        "relationships-exact" -> RelationshipsTimeline.totalFrameCount(project, spec)',
    "timeline frame-count dispatch",
)
bridge = replace_once(
    bridge,
    '    private fun renderEngine(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap =\n        when (engine(spec)) {\n            "relationships-exact" ->',
    '    private fun renderEngine(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap =\n        when (engine(spec)) {\n            "infinite-timeline-exact" -> infiniteRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))\n            "relationships-exact" ->',
    "bitmap render dispatch",
)
bridge = replace_once(
    bridge,
    '    private fun renderEngineRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray =\n        when (engine(spec)) {\n            "relationships-exact" ->',
    '    private fun renderEngineRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray =\n        when (engine(spec)) {\n            "infinite-timeline-exact" -> infiniteRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))\n            "relationships-exact" ->',
    "rgba render dispatch",
)
BRIDGE.write_text(bridge)

gpu = GPU.read_text()
gpu = replace_once(
    gpu,
    '    private val nativeRenderer = bridgeField("nativeRenderer")\n    private val ribbonRenderer = bridgeField("ribbonRenderer")',
    '    private val nativeRenderer = bridgeField("nativeRenderer")\n    private val infiniteRenderer = bridgeField("infiniteRenderer")\n    private val ribbonRenderer = bridgeField("ribbonRenderer")',
    "direct GPU infinite field",
)
gpu = replace_once(
    gpu,
    '            when {\n                RelationshipsTimeline.isRelationships(spec) && RelationshipsPrecisionFrameRenderer.enabled(spec) ->',
    '            when {\n                InfiniteTimeline.isInfinite(spec) ->\n                    drawFourArg(infiniteRenderer, canvas, project, engineFrame, spec)\n                RelationshipsTimeline.isRelationships(spec) && RelationshipsPrecisionFrameRenderer.enabled(spec) ->',
    "direct GPU engine dispatch",
)
GPU.write_text(gpu)

print("Registered Infinite Timeline source-exact v2 engine in capabilities, bridge and direct GPU export")
