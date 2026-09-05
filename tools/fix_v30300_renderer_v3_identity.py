#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
bundle_path = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
import_path = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererImportActivity.kt"

text = bundle_path.read_text()
old = '    val name: String = "Cubical Compare 2.0.8 Native",\n'
new = '    val name: String = "Cubical Compare 3.0.300 Native",\n'
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("Built-in renderer identity marker changed")

# API 2 ribbon renderers already consume the shine geometry/frame-addressed shine
# contract in RibbonFrameRenderer. 3.0.300 was rejecting the source-exact Hand
# Dissolve renderer only because the capability alias was missing from this registry.
feature = '        "ribbon-glass-shine-v1",\n'
feature_anchor = '        "frame-addressed-shine",\n'
if feature not in text:
    if text.count(feature_anchor) != 1:
        raise SystemExit("Renderer feature registry anchor changed")
    text = text.replace(feature_anchor, feature_anchor + feature, 1)

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
    '"ribbon-glass-shine-v1"',
)
missing = [needle for needle in required if needle not in text]
if missing:
    raise SystemExit(f"Renderer v3 capability contract incomplete: {missing}")

bundle_path.write_text(text)

# Do not tell users that a renderer needs a newer app when the app-version check
# actually passed and a different compatibility contract is the blocker.
ui = import_path.read_text()
old_ui = '''        val installedVersion = BuildConfig.VERSION_NAME
        Text(
            if (pending.report.compatible) {
                "Ready for Cubical Compare $installedVersion"
            } else {
                "Can't use this renderer on $installedVersion • requires ${spec.minAppVersion}+"
            },
            fontWeight = FontWeight.SemiBold,
            color = if (pending.report.compatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
'''
new_ui = '''        val installedVersion = BuildConfig.VERSION_NAME
        val versionBlocked = pending.report.errors.any { it.startsWith("Requires Cubical Compare ") }
        Text(
            when {
                pending.report.compatible -> "Ready for Cubical Compare $installedVersion"
                versionBlocked -> "Requires Cubical Compare ${spec.minAppVersion}+ • installed $installedVersion"
                else -> "Renderer isn't compatible with Cubical Compare $installedVersion"
            },
            fontWeight = FontWeight.SemiBold,
            color = if (pending.report.compatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
'''
if new_ui not in ui:
    if ui.count(old_ui) != 1:
        raise SystemExit("Renderer import compatibility-message anchor changed")
    ui = ui.replace(old_ui, new_ui, 1)
import_path.write_text(ui)

print("Cubical Compare 3.0.300 Renderer v3 identity/capabilities/import UX verified")
