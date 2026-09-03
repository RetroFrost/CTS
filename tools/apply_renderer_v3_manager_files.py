#!/usr/bin/env python3
"""Make .renderer3 a first-class file type in the Renderer Manager UI."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererManagerActivity.kt"

text = PATH.read_text()
text = text.replace(
    'importRenderer.launch(arrayOf("application/octet-stream", "*/*"))',
    'importRenderer.launch(arrayOf(RendererImportActivity.RENDERER_MIME, RendererImportActivity.RENDERER_V3_MIME, "application/zip", "application/octet-stream", "*/*"))',
)
text = text.replace(') { Text("Inspect .renderer") }', ') { Text("Inspect .renderer / .renderer3") }')
text = text.replace(
    'onClick = { exportRenderer.launch("${active.id}.renderer") },',
    'onClick = { exportRenderer.launch("${active.id}.${if (active.rendererApi >= 3) "renderer3" else "renderer"}") },',
)
PATH.write_text(text)
print("Applied Renderer API v3 file handling in Renderer Manager")
