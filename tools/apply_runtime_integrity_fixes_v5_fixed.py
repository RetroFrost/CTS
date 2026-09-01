#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
source_path = ROOT / "tools/apply_runtime_integrity_fixes_v5.py"
source = source_path.read_text()

old = 'text = replace_once(text, old_front, new_front, "front renderer artwork region")'
new = '''if new_front not in text:
    start = text.find("    private fun drawFrontArtwork(")
    end = text.find("    private fun drawArtwork(", start)
    if start < 0 or end < 0:
        raise SystemExit("front renderer artwork function markers changed")
    text = text[:start] + new_front + "\\n" + text[end:]'''
if old not in source:
    raise SystemExit("v5 front-artwork patch marker changed")
source = source.replace(old, new, 1)

exec(compile(source, str(source_path), "exec"), {"__name__": "__main__", "__file__": str(source_path)})
