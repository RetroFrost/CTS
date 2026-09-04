#!/usr/bin/env python3
"""Keep the 3.0.300 recovery baseline internally build-compatible.

The branch contains the legacy RendererBundle.read(InputStream) API, but a later
low-memory merge changed ExportService to call a File overload that is not present in
this recovery baseline. That mismatch prevents the otherwise-working app from
compiling. Restore the baseline stream call only; this script deliberately does not
replay the broader low-memory migration.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/ExportService.kt"
text = PATH.read_text()

old = '''                // Keep Renderer v3 packages disk-backed during export. The stream
                // overload materializes its input for legacy callers, while the
                // file overload lazily opens v3 sidecars from the snapshot ZIP.
                val spec = RendererBundle.read(rendererFile)
'''
new = '''                val spec = rendererFile.inputStream().use(RendererBundle::read)
'''

if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("3.0.300 export reader call shape changed; refusing an unsafe compatibility patch")

# This recovery workflow intentionally targets the user-verified pre-low-memory
# runtime. If the File overload is genuinely present later, this guard can be removed
# together with this compatibility script.
renderer_bundle = (
    ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
).read_text()
if 'fun read(file: File)' in renderer_bundle:
    raise SystemExit("RendererBundle now has a File reader; remove this recovery compatibility patch instead of downgrading it")

PATH.write_text(text)
print("3.0.300 export reader compatibility verified")
