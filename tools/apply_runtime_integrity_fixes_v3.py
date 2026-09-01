#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/NativeFrameRenderer.kt"

text = PATH.read_text()
legacy = "        if (count == 57 && spec.continuousStartFrame == 528 && spec.continuousStepFrames == 214) return 11_858\n"
if legacy in text:
    text = text.replace(legacy, "", 1)
if "count == 57" in text:
    raise SystemExit("archived 57-card native timing rule still present")
PATH.write_text(text)
print("Removed archived 57-card native timing exception")
