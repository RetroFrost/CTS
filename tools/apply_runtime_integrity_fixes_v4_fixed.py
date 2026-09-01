#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source_path = ROOT / "tools/apply_runtime_integrity_fixes_v4.py"
source = source_path.read_text()
wrong = 'path = ANDROID / "../androidTest/java/io/github/retrofrost/cts/android/RuntimeIntegrityInstrumentedTest.kt"\npath = path.resolve()'
right = 'path = ROOT / "android/app/src/androidTest/java/io/github/retrofrost/cts/android/RuntimeIntegrityInstrumentedTest.kt"'
if wrong not in source:
    raise SystemExit("v4 instrumentation path marker changed")
source = source.replace(wrong, right, 1)
exec(compile(source, str(source_path), "exec"), {"__name__": "__main__", "__file__": str(source_path)})
