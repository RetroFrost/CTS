#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RIBBON = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RibbonFrameRenderer.kt"
BUNDLE = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"

TAG = "puberty-badge-source-lock-v3"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


ribbon = RIBBON.read_text()

path_marker = '''    private val badgePath = Path().apply {
        moveTo(224f, 16f)
        lineTo(396f, 104f)
        lineTo(396f, 292f)
        lineTo(252f, 380f)
        lineTo(72f, 292f)
        lineTo(72f, 104f)
        close()
    }
'''
path_replacement = path_marker + '''    private val pubertyLaterBadgePath = Path().apply {
        moveTo(236f, 12f)
        lineTo(397f, 105f)
        lineTo(397f, 292f)
        lineTo(237f, 385f)
        lineTo(75f, 292f)
        lineTo(75f, 105f)
        close()
    }
'''
ribbon = replace_once(ribbon, path_marker, path_replacement, "source badge polygon")

ribbon = replace_once(
    ribbon,
    "        val local = globalFrame - start\n\n        val age: Float",
    "        val local = globalFrame - start\n        val sourceLockedBadge = spec.tags.contains(\"puberty-badge-source-lock-v3\")\n\n        val age: Float",
    "source lock flag",
)

# Two supported source shapes exist. Older branches use the fallback entry window;
# current integrity-patched branches let exact renderer tracks own their start frame.
# In both cases source-locked Puberty badges are already full-size while moving.
if "age = if (sourceLockedBadge) BADGE_ENTRY_AGE" not in ribbon:
    old_integrity_age = '''            age = (local - (exactBadgeStart ?: spec.laterBadgeFallStartFrame)).toFloat() / 103f * 2.25f
'''
    new_integrity_age = '''            age = if (sourceLockedBadge) BADGE_ENTRY_AGE else
                (local - (exactBadgeStart ?: spec.laterBadgeFallStartFrame)).toFloat() / 103f * 2.25f
'''
    if old_integrity_age in ribbon:
        ribbon = ribbon.replace(old_integrity_age, new_integrity_age, 1)
    else:
        ribbon = replace_once(
            ribbon,
            "        } else {\n            if (local < spec.laterBadgeFallStartFrame) return\n            age = (local - spec.laterBadgeFallStartFrame).toFloat() / 103f * 2.25f\n            matrix.setTranslate(0f, motionTrack(spec, \"ribbon.card.$index.badge.y\", local) ?: motionTrack(spec, \"ribbon.later.badge.y\", local) ?: laterBadgeYOffset(local))\n        }",
            "        } else {\n            if (local < spec.laterBadgeFallStartFrame) return\n            age = if (sourceLockedBadge) BADGE_ENTRY_AGE else\n                (local - spec.laterBadgeFallStartFrame).toFloat() / 103f * 2.25f\n            matrix.setTranslate(0f, motionTrack(spec, \"ribbon.card.$index.badge.y\", local) ?: motionTrack(spec, \"ribbon.later.badge.y\", local) ?: laterBadgeYOffset(local))\n        }",
            "later badge source motion",
        )

ribbon = replace_once(
    ribbon,
    "        canvas.save()\n        canvas.translate(cardX, 0f)",
    "        canvas.save()\n        if (sourceLockedBadge) {\n            // The source badge is full-size and moves behind the dark artwork lane.\n            canvas.clipRect(0f, 0f, REFERENCE_WIDTH.toFloat(), spec.imageHeight)\n        }\n        canvas.translate(cardX, 0f)",
    "badge lane clip",
)

ribbon = replace_once(
    ribbon,
    "        val path = badgePath\n",
    "        val path = if (index >= 4 && spec.tags.contains(\"puberty-badge-source-lock-v3\")) pubertyLaterBadgePath else badgePath\n",
    "badge source path selection",
)
RIBBON.write_text(ribbon)

bundle = BUNDLE.read_text()
if '"puberty-badge-source-lock-v3"' not in bundle:
    bundle = replace_once(
        bundle,
        '        "preview-frames",\n',
        '        "preview-frames",\n        "puberty-badge-source-lock-v3",\n',
        "renderer capability",
    )
BUNDLE.write_text(bundle)

print("Puberty source-locked badge migration is present and idempotent across integrity timing")