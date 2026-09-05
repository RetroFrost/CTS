#!/usr/bin/env python3
"""Keep the packaged desktop engine's CCX bridge aligned with CTS 3.0.300.

The engine Card model already owns badge_header, but the legacy CCX CLI bridge
predates that field. The native C++ Windows editor uses CCX only as an internal
renderer snapshot, so add the missing field without changing the public JSON v6
project contract.
"""
from pathlib import Path

path = Path("engine/engine_cli.py")
text = path.read_text(encoding="utf-8")

write_old = '''        puts(f"card.{index}.value", card.value)\n        puts(f"card.{index}.description", card.description)'''
write_new = '''        puts(f"card.{index}.value", card.value)\n        puts(f"card.{index}.badge_header", card.badge_header)\n        puts(f"card.{index}.description", card.description)'''
read_old = '''            value=gets(f"card.{index}.value"),\n            description=gets(f"card.{index}.description"),'''
read_new = '''            value=gets(f"card.{index}.value"),\n            badge_header=gets(f"card.{index}.badge_header"),\n            description=gets(f"card.{index}.description"),'''

for label, old, new in (("CCX writer", write_old, write_new), ("CCX reader", read_old, read_new)):
    if new in text:
        continue
    if old not in text:
        raise SystemExit(f"Could not patch {label}: expected source block not found")
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("CTS 3.0.300 Windows CCX badge-header bridge applied")
