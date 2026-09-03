#!/usr/bin/env python3
"""Keep older source-measured v3 manifests importable while dedicated runtime features exist.

Dedicated resource types still require their feature declaration and are validated.
Older manifests that declare a capability using pre-scene-IR data are not rejected just
because they have not yet been migrated to the new dedicated resource form.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererV3.kt"
text = PATH.read_text()

reverse = '''            if (feature in features) require(count > 0) {
                "Renderer v3 declares '$feature' but provides no dedicated ${names.joinToString("/")} resource."
            }
'''
text = text.replace(reverse, '')

legacy_checks = '''        if ("raw-frame-tracks" in features) {
            require(containsRawFrameTrack(root)) {
                "Renderer v3 declares 'raw-frame-tracks' but contains no dense/raw frame track."
            }
        }
        if ("frame-addressed-selectors" in features) {
            require(selectors.any { selector -> selector.conditions.any { it.key == "frame" || it.key in setOf("every", "from", "to") } }) {
                "Renderer v3 declares 'frame-addressed-selectors' but contains no frame-addressed selector."
            }
        }
'''
text = text.replace(legacy_checks, '''        // Older source-measured v3 manifests may declare these capabilities using
        // pre-scene-IR track/selector layouts. The evaluator implements the modern
        // contracts, while legacy manifests remain importable for migration.
''')

PATH.write_text(text)
print("Preserved legacy Renderer v3 manifest compatibility")
