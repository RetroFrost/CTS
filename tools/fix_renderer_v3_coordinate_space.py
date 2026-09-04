#!/usr/bin/env python3
"""Repair Renderer API v3 local/object coordinate-space composition.

RendererV3FrameRenderer applies position.x/position.y plus movement.x/movement.y to
Canvas before dispatching a resource draw. Image, text, and source-text-raster draws
must therefore use only their resource-local x/y. Falling back to position.x/y in
those draw routines translates the resource a second time.

The Puberty 1.4 scene exposes this immediately: at frame 576 card@1 has
position.x=487 and selector movement.x=-96. The object transform correctly places it
at x=391, but the old image path then added position.x=487 again, drawing the body at
x=878 while the polygon badge stayed at x=381. The same bug shifted badge text onto
the following badge.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererV3FrameRenderer.kt"
text = PATH.read_text()

replacements = (
    (
        'val x = number(props["x"] ?: props["position.x"], resource.optDouble("x", 0.0)).toFloat()',
        'val x = number(props["x"], resource.optDouble("x", 0.0)).toFloat()',
        3,
        "local x origin",
    ),
    (
        'val y = number(props["y"] ?: props["position.y"], resource.optDouble("y", 0.0)).toFloat()',
        'val y = number(props["y"], resource.optDouble("y", 0.0)).toFloat()',
        3,
        "local y origin",
    ),
)

changed = False
for old, new, expected, label in replacements:
    old_count = text.count(old)
    new_count = text.count(new)
    if old_count == expected:
        text = text.replace(old, new)
        changed = True
    elif old_count == 0 and new_count >= expected:
        # Idempotent on an already-fixed tree.
        pass
    else:
        raise SystemExit(
            f"Renderer v3 {label} contract changed: old={old_count}, new={new_count}, expected={expected}"
        )

# The one and only place where object position is converted into a Canvas transform.
transform_x = 'val x = number(props["position.x"], 0.0).toFloat() + number(props["movement.x"], 0.0).toFloat()'
transform_y = 'val y = number(props["position.y"], 0.0).toFloat() + number(props["movement.y"], 0.0).toFloat()'
if text.count(transform_x) != 1 or text.count(transform_y) != 1:
    raise SystemExit("Renderer v3 object-transform ownership changed; refusing an unsafe coordinate patch")

# Guard against reintroducing the exact double-translation pattern anywhere in a
# local resource draw path.
for forbidden in (
    'props["x"] ?: props["position.x"]',
    'props["y"] ?: props["position.y"]',
):
    if forbidden in text:
        raise SystemExit(f"Renderer v3 still double-applies object position: {forbidden}")

PATH.write_text(text)
state = "patched" if changed else "already fixed"
print(f"Renderer v3 coordinate spaces verified: {state}; object position is applied exactly once")
