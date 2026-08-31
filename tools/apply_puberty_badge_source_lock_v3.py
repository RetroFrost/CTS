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
ribbon = replace_once(
    ribbon,
    "        val local = globalFrame - start\n\n        val age: Float",
    "        val local = globalFrame - start\n        val sourceLockedBadge = spec.tags.contains(\"puberty-badge-source-lock-v3\")\n\n        val age: Float",
    "source lock flag",
)

# Later badges in the Puberty source are already full-size, fully-typeset badges.
# They rise vertically from below the artwork/title boundary and are clipped by
# that boundary. The old fallback grew the badge and animated its text separately,
# which can look similar in isolated frames but is not the source animation.
ribbon = replace_once(
    ribbon,
    "        } else {\n            if (local < spec.laterBadgeFallStartFrame) return\n            age = (local - spec.laterBadgeFallStartFrame).toFloat() / 103f * 2.25f\n            matrix.setTranslate(0f, motionTrack(spec, \"ribbon.card.$index.badge.y\", local) ?: motionTrack(spec, \"ribbon.later.badge.y\", local) ?: laterBadgeYOffset(local))\n        }",
    "        } else {\n            if (local < spec.laterBadgeFallStartFrame) return\n            age = if (sourceLockedBadge) BADGE_ENTRY_AGE else\n                (local - spec.laterBadgeFallStartFrame).toFloat() / 103f * 2.25f\n            matrix.setTranslate(0f, motionTrack(spec, \"ribbon.card.$index.badge.y\", local) ?: motionTrack(spec, \"ribbon.later.badge.y\", local) ?: laterBadgeYOffset(local))\n        }",
    "later badge source motion",
)

ribbon = replace_once(
    ribbon,
    "        canvas.save()\n        canvas.translate(cardX, 0f)",
    "        canvas.save()\n        if (sourceLockedBadge) {\n            // In the source video the badge layer is masked to the dark artwork\n            // lane. During entry only the tip is initially visible; the full-size\n            // badge itself is moving behind this mask.\n            canvas.clipRect(0f, 0f, REFERENCE_WIDTH.toFloat(), spec.imageHeight)\n        }\n        canvas.translate(cardX, 0f)",
    "badge lane clip",
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

print("Applied Puberty source-locked badge masking and attached-text motion")
