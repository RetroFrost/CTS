from pathlib import Path

# ExportService uses the StateFlow extension after the primary rewrite.
service = Path("android/app/src/main/java/io/github/retrofrost/cts/android/ExportService.kt")
text = service.read_text()
if "import kotlinx.coroutines.flow.asStateFlow" not in text:
    text = text.replace(
        "import kotlinx.coroutines.launch\n",
        "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.flow.asStateFlow\n",
        1,
    )
service.write_text(text)

# Keep the user-editable Project page showing project settings, but make the
# Export card show the actual renderer-resolved raster and cadence.
main = Path("android/app/src/main/java/io/github/retrofrost/cts/android/MainActivity.kt")
text = main.read_text()
resolved_row = 'SettingRow("Output", "${outputProject.width}×${outputProject.height} · ${outputProject.fps} FPS")'
project_row = 'SettingRow("Output", "${project.width}×${project.height} · ${project.fps} FPS")'
if resolved_row not in text:
    raise SystemExit("resolved output row not found")
text = text.replace(resolved_row, project_row, 1)
split = text.index("private fun MorePage(")
before, after = text[:split], text[split:]
if project_row not in after:
    raise SystemExit("export output row not found")
after = after.replace(project_row, resolved_row, 1)
main.write_text(before + after)

# Pure JVM-testable policy. RendererBridge delegates to this, so production
# preview/metadata/export and the regression suite exercise the same rule.
policy = Path("android/app/src/main/java/io/github/retrofrost/cts/android/RenderOutputPolicy.kt")
policy.write_text(
    """package io.github.retrofrost.cts.android

/** Pure output policy shared by preview, metadata and export. */
internal object RenderOutputPolicy {
    fun resolve(project: StudioProject, spec: RendererSpec): StudioProject =
        if (spec.precisionMode == \"frame-exact\") {
            project.copy(
                width = spec.referenceWidth.coerceAtLeast(2),
                height = spec.referenceHeight.coerceAtLeast(2),
                fps = spec.referenceFps.coerceIn(1, 240),
            )
        } else {
            project.copy(
                width = project.width.coerceAtLeast(2),
                height = project.height.coerceAtLeast(2),
                fps = project.fps.coerceIn(1, 120),
            )
        }
}
"""
)

bridge = Path("android/app/src/main/java/io/github/retrofrost/cts/android/RendererBridge.kt")
text = bridge.read_text()
start = text.index("    fun resolveOutputProject(")
end = text.index("\n\n    fun projectCompatibility(", start)
replacement = """    fun resolveOutputProject(
        project: StudioProject,
        spec: RendererSpec = RendererRuntime.active,
    ): StudioProject = RenderOutputPolicy.resolve(project, spec)"""
bridge.write_text(text[:start] + replacement + text[end:])

# The primary patch creates the regression test; make it pure and ensure its
# assertion import exists.
test = Path("android/app/src/test/java/io/github/retrofrost/cts/android/RelationshipsPrecisionRendererTest.kt")
text = test.read_text()
if "import org.junit.Assert.assertEquals" not in text:
    text = text.replace(
        "import org.junit.Assert.assertFalse\n",
        "import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\n",
        1,
    )
text = text.replace(
    "RendererBridge.resolveOutputProject(project, spec)",
    "RenderOutputPolicy.resolve(project, spec)",
)
test.write_text(text)

# MediaCodec encoder capabilities are nullable on the Android API surface.
codec = Path("android/app/src/main/java/io/github/retrofrost/cts/android/HardwareVideoExporter.kt")
text = codec.read_text()
text = text.replace(
    "runCatching { encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) }.getOrDefault(false)",
    "encoderCapabilities?.let { runCatching { it.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) }.getOrDefault(false) } == true",
)
text = text.replace(
    "runCatching { encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) }.getOrDefault(false)",
    "encoderCapabilities?.let { runCatching { it.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) }.getOrDefault(false) } == true",
)
codec.write_text(text)
