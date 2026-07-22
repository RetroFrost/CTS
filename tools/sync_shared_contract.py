#!/usr/bin/env python3
from __future__ import annotations

"""Generate and verify the shared CTS Android-desktop contract."""

import argparse
import json
import re
import runpy
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "shared" / "cts_contract.json"
PYTHON_PATH = ROOT / "comparison_studio" / "shared_contract.py"
KOTLIN_PATH = (
    ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "io"
    / "github"
    / "retrofrost"
    / "cts"
    / "android"
    / "shared"
    / "SharedContract.kt"
)
PROGRAM_MONITOR_PATH = (
    ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "io"
    / "github"
    / "retrofrost"
    / "cts"
    / "android"
    / "ui"
    / "ProgramMonitor.kt"
)

TIMING_CONSTANTS = {
    "REVEAL_SECONDS": "reveal_seconds",
    "SCROLL_SECONDS": "scroll_seconds",
    "END_HOLD_SECONDS": "end_hold_seconds",
    "FADE_SECONDS": "fade_seconds",
    "BODY_WIPE_SECONDS": "body_wipe_seconds",
    "BADGE_DELAY_SECONDS": "badge_delay_seconds",
    "BADGE_SETTLE_SECONDS": "badge_settle_seconds",
    "INTRO_TAIL_HOLD_SECONDS": "intro_tail_hold_seconds",
}
FRAME_CONSTANTS = {
    "image_frame": "IMAGE",
    "title_frame": "TITLE",
    "description_frame": "DESCRIPTION",
    "badge_frame": "BADGE",
}
COLOR_CONSTANTS = {
    "background": "COLOR_BACKGROUND",
    "image_top": "COLOR_IMAGE_TOP",
    "image_bottom": "COLOR_IMAGE_BOTTOM",
    "title_background": "COLOR_TITLE_BACKGROUND",
    "title_text": "COLOR_TITLE_TEXT",
    "description_background": "COLOR_DESCRIPTION_BACKGROUND",
    "description_text": "COLOR_DESCRIPTION_TEXT",
    "divider": "COLOR_DIVIDER",
    "badge_top": "COLOR_BADGE_TOP",
    "badge_middle": "COLOR_BADGE_MIDDLE",
    "badge_bottom": "COLOR_BADGE_BOTTOM",
    "badge_border": "COLOR_BADGE_BORDER",
}


def load_spec() -> dict[str, Any]:
    return json.loads(SPEC_PATH.read_text(encoding="utf-8"))


def quoted(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def render_python(spec: dict[str, Any]) -> str:
    model = spec["canonical_model"]
    timing = spec["timing"]
    layout = spec["layout"]
    legacy = ", ".join(quoted(value) for value in model["legacy_ids"])
    fields = ", ".join(quoted(value) for value in spec["fields"])
    colors = "\n".join(
        f"    {quoted(name)}: {quoted(value)}," for name, value in spec["colors"].items()
    )
    samples = "\n".join(
        "    SharedCard({primary}, {secondary}, {title}, {description}),".format(
            primary=quoted(card["badge_primary"]),
            secondary=quoted(card["badge_secondary"]),
            title=quoted(card["title"]),
            description=quoted(card["description"]),
        )
        for card in spec["sample_cards"]
    )
    frame = lambda name: ", ".join(str(float(value)) for value in layout[name])
    ease = ", ".join(str(float(value)) for value in timing["material_ease"])
    return f'''from __future__ import annotations

"""Generated from shared/cts_contract.json. Do not edit by hand."""

from dataclasses import dataclass
from math import floor

CONTRACT_VERSION = {int(spec["contract_version"])}
PROJECT_VERSION = {int(spec["project_version"])}
MODEL_ID = {quoted(model["id"])}
MODEL_LABEL = {quoted(model["label"])}
VISIBLE_CARDS = {int(model["visible_cards"])}
LEGACY_MODEL_IDS = ({legacy})
FIELDS = ({fields})

REVEAL_SECONDS = {float(timing["reveal_seconds"])}
SCROLL_SECONDS = {float(timing["scroll_seconds"])}
END_HOLD_SECONDS = {float(timing["end_hold_seconds"])}
FADE_SECONDS = {float(timing["fade_seconds"])}
BODY_WIPE_SECONDS = {float(timing["body_wipe_seconds"])}
BADGE_DELAY_SECONDS = {float(timing["badge_delay_seconds"])}
BADGE_SETTLE_SECONDS = {float(timing["badge_settle_seconds"])}
INTRO_TAIL_HOLD_SECONDS = {float(timing["intro_tail_hold_seconds"])}
MATERIAL_EASE = ({ease})

IMAGE_FRAME = ({frame("image_frame")})
TITLE_FRAME = ({frame("title_frame")})
DESCRIPTION_FRAME = ({frame("description_frame")})
BADGE_FRAME = ({frame("badge_frame")})

COLORS = {{
{colors}
}}


@dataclass(frozen=True, slots=True)
class SharedCard:
    badge_primary: str
    badge_secondary: str
    title: str
    description: str


SAMPLE_CARDS = (
{samples}
)


def normalize_model_id(_value: str | None) -> str:
    return MODEL_ID


def automatic_duration(card_count: int) -> float:
    if card_count <= 0:
        return 0.0
    reveal = min(card_count, VISIBLE_CARDS) * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
    scroll = max(0, card_count - VISIBLE_CARDS) * SCROLL_SECONDS
    return reveal + scroll + END_HOLD_SECONDS + FADE_SECONDS


def chosen_duration(card_count: int, custom_duration: float | None) -> float:
    automatic = automatic_duration(card_count)
    return max(1.0, float(custom_duration)) if custom_duration is not None else automatic


def model_time(card_count: int, output_time: float, custom_duration: float | None) -> float:
    automatic = automatic_duration(card_count)
    chosen = chosen_duration(card_count, custom_duration)
    speed = automatic / chosen if automatic > 0.0 and chosen > 0.0 else 1.0
    return max(0.0, float(output_time)) * speed


def editing_time_for_card(card_count: int, card_index: int, custom_duration: float | None) -> float:
    if card_count <= 0:
        return 0.0
    safe_index = max(0, min(card_index, card_count - 1))
    initial_count = min(card_count, VISIBLE_CARDS)
    scroll_start = initial_count * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
    target_model_time = scroll_start if safe_index < VISIBLE_CARDS else scroll_start + (safe_index - VISIBLE_CARDS + 1) * SCROLL_SECONDS
    automatic = automatic_duration(card_count)
    chosen = chosen_duration(card_count, custom_duration)
    speed = automatic / chosen if automatic > 0.0 and chosen > 0.0 else 1.0
    return min(chosen, target_model_time / max(0.001, speed))


def material_ease(value: float) -> float:
    x = max(0.0, min(1.0, float(value)))
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    x1, y1, x2, y2 = MATERIAL_EASE
    low, high = 0.0, 1.0
    for _ in range(12):
        t = (low + high) / 2.0
        if _cubic(t, x1, x2) < x:
            low = t
        else:
            high = t
    return _cubic((low + high) / 2.0, y1, y2)


def placement_shift(model_elapsed: float, maximum_shift: int) -> float:
    raw = min(float(maximum_shift), max(0.0, model_elapsed) / SCROLL_SECONDS)
    completed = min(maximum_shift, floor(raw))
    return float(maximum_shift) if completed >= maximum_shift else completed + material_ease(raw - completed)


def _cubic(t: float, first_control: float, second_control: float) -> float:
    inverse = 1.0 - t
    return 3.0 * inverse * inverse * t * first_control + 3.0 * inverse * t * t * second_control + t * t * t
'''


def render_kotlin(spec: dict[str, Any]) -> str:
    model = spec["canonical_model"]
    timing = spec["timing"]
    layout = spec["layout"]
    legacy = ", ".join(quoted(value) for value in model["legacy_ids"])
    fields = ", ".join(quoted(value) for value in spec["fields"])
    frame_lines: list[str] = []
    for key, prefix in FRAME_CONSTANTS.items():
        x, y, width, height = (float(value) for value in layout[key])
        frame_lines.extend(
            [
                f"    const val {prefix}_X = {x}f",
                f"    const val {prefix}_Y = {y}f",
                f"    const val {prefix}_WIDTH = {width}f",
                f"    const val {prefix}_HEIGHT = {height}f",
            ]
        )
    color_lines = [
        f"    const val {COLOR_CONSTANTS[name]} = {quoted(value)}"
        for name, value in spec["colors"].items()
    ]
    samples = "\n".join(
        """    SharedSampleCard(
        badgePrimary = {primary},
        badgeSecondary = {secondary},
        title = {title},
        description = {description},
    ),""".format(
            primary=quoted(card["badge_primary"]),
            secondary=quoted(card["badge_secondary"]),
            title=quoted(card["title"]),
            description=quoted(card["description"]),
        )
        for card in spec["sample_cards"]
    )
    ease = timing["material_ease"]
    return f'''package io.github.retrofrost.cts.android.shared

/** Generated from shared/cts_contract.json. Do not edit by hand. */
object SharedContract {{
    const val CONTRACT_VERSION = {int(spec["contract_version"])}
    const val PROJECT_VERSION = {int(spec["project_version"])}
    const val MODEL_ID = {quoted(model["id"])}
    const val MODEL_LABEL = {quoted(model["label"])}
    const val VISIBLE_CARDS = {int(model["visible_cards"])}

    val LEGACY_MODEL_IDS = setOf({legacy})
    val FIELDS = listOf({fields})

    const val REVEAL_SECONDS = {float(timing["reveal_seconds"])}f
    const val SCROLL_SECONDS = {float(timing["scroll_seconds"])}f
    const val END_HOLD_SECONDS = {float(timing["end_hold_seconds"])}f
    const val FADE_SECONDS = {float(timing["fade_seconds"])}f
    const val BODY_WIPE_SECONDS = {float(timing["body_wipe_seconds"])}f
    const val BADGE_DELAY_SECONDS = {float(timing["badge_delay_seconds"])}f
    const val BADGE_SETTLE_SECONDS = {float(timing["badge_settle_seconds"])}f
    const val INTRO_TAIL_HOLD_SECONDS = {float(timing["intro_tail_hold_seconds"])}f

    const val MATERIAL_EASE_X1 = {float(ease[0])}f
    const val MATERIAL_EASE_Y1 = {float(ease[1])}f
    const val MATERIAL_EASE_X2 = {float(ease[2])}f
    const val MATERIAL_EASE_Y2 = {float(ease[3])}f

{chr(10).join(frame_lines)}

{chr(10).join(color_lines)}
}}

data class SharedSampleCard(
    val badgePrimary: String,
    val badgeSecondary: String,
    val title: String,
    val description: String,
)

val SHARED_SAMPLE_CARDS = listOf(
{samples}
)
'''


def _kotlin_constant(source: str, name: str) -> str | None:
    match = re.search(rf"(?:const )?val {re.escape(name)}(?:\s*:\s*\w+)?\s*=\s*([^\n]+)", source)
    return match.group(1).strip() if match else None


def _float_literal(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        return float(value.rstrip("fF"))
    except ValueError:
        return None


def semantic_check(spec: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    python_values = runpy.run_path(str(PYTHON_PATH))
    model = spec["canonical_model"]
    timing = spec["timing"]
    layout = spec["layout"]

    expected_python: dict[str, Any] = {
        "CONTRACT_VERSION": spec["contract_version"],
        "PROJECT_VERSION": spec["project_version"],
        "MODEL_ID": model["id"],
        "MODEL_LABEL": model["label"],
        "VISIBLE_CARDS": model["visible_cards"],
        "LEGACY_MODEL_IDS": tuple(model["legacy_ids"]),
        "FIELDS": tuple(spec["fields"]),
        "MATERIAL_EASE": tuple(float(value) for value in timing["material_ease"]),
        "IMAGE_FRAME": tuple(float(value) for value in layout["image_frame"]),
        "TITLE_FRAME": tuple(float(value) for value in layout["title_frame"]),
        "DESCRIPTION_FRAME": tuple(float(value) for value in layout["description_frame"]),
        "BADGE_FRAME": tuple(float(value) for value in layout["badge_frame"]),
        "COLORS": spec["colors"],
    }
    for constant, key in TIMING_CONSTANTS.items():
        expected_python[constant] = timing[key]
    for name, expected in expected_python.items():
        if python_values.get(name) != expected:
            errors.append(f"desktop {name} is {python_values.get(name)!r}, expected {expected!r}")

    python_cards = [
        {
            "badge_primary": card.badge_primary,
            "badge_secondary": card.badge_secondary,
            "title": card.title,
            "description": card.description,
        }
        for card in python_values["SAMPLE_CARDS"]
    ]
    if python_cards != spec["sample_cards"]:
        errors.append("desktop sample cards do not match the shared contract")

    kotlin = KOTLIN_PATH.read_text(encoding="utf-8")
    string_constants = {
        "MODEL_ID": model["id"],
        "MODEL_LABEL": model["label"],
        **{constant: value for value, constant in COLOR_CONSTANTS.items()},
    }
    integer_constants = {
        "CONTRACT_VERSION": spec["contract_version"],
        "PROJECT_VERSION": spec["project_version"],
        "VISIBLE_CARDS": model["visible_cards"],
    }
    for name, expected in string_constants.items():
        if _kotlin_constant(kotlin, name) != quoted(expected):
            errors.append(f"Android {name} does not match the shared contract")
    for name, expected in integer_constants.items():
        if _kotlin_constant(kotlin, name) != str(expected):
            errors.append(f"Android {name} does not match the shared contract")
    for name, key in TIMING_CONSTANTS.items():
        if _float_literal(_kotlin_constant(kotlin, name)) != float(timing[key]):
            errors.append(f"Android {name} does not match the shared contract")
    for index, suffix in enumerate(("X1", "Y1", "X2", "Y2")):
        name = f"MATERIAL_EASE_{suffix}"
        if _float_literal(_kotlin_constant(kotlin, name)) != float(timing["material_ease"][index]):
            errors.append(f"Android {name} does not match the shared contract")
    for key, prefix in FRAME_CONSTANTS.items():
        for suffix, expected in zip(("X", "Y", "WIDTH", "HEIGHT"), layout[key]):
            name = f"{prefix}_{suffix}"
            if _float_literal(_kotlin_constant(kotlin, name)) != float(expected):
                errors.append(f"Android {name} does not match the shared contract")

    for value in model["legacy_ids"] + spec["fields"]:
        if quoted(value) not in kotlin:
            errors.append(f"Android adapter is missing {value!r}")
    for card in spec["sample_cards"]:
        for value in card.values():
            if quoted(value) not in kotlin:
                errors.append(f"Android adapter is missing sample value {value!r}")

    monitor = PROGRAM_MONITOR_PATH.read_text(encoding="utf-8")
    for key, class_name in {
        "image_frame": "ImageFrame",
        "title_frame": "TitleFrame",
        "description_frame": "DescriptionFrame",
        "badge_frame": "BadgeFrame",
    }.items():
        match = re.search(rf"private val {class_name}\s*=\s*NormalizedRect\(([^)]]+)\)", monitor)
        actual = None
        if match:
            actual = tuple(float(value.strip().rstrip("fF")) for value in match.group(1).split(","))
        expected = tuple(float(value) for value in layout[key])
        if actual != expected:
            errors.append(f"Android ProgramMonitor {class_name} does not match the shared layout")
    for name, value in spec["colors"].items():
        if value.upper() in {"#000000", "#FFFFFF"}:
            continue
        compose_hex = "0xFF" + value.lstrip("#").upper()
        if compose_hex not in monitor.upper():
            errors.append(f"Android ProgramMonitor is missing shared color {name}={value}")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail when either platform drifts")
    args = parser.parse_args()
    spec = load_spec()
    if args.check:
        errors = semantic_check(spec)
        if errors:
            print("CTS shared contract drift detected:", file=sys.stderr)
            for error in errors:
                print(f"- {error}", file=sys.stderr)
            return 1
        print("CTS Android and desktop contract adapters are in sync.")
        return 0

    PYTHON_PATH.write_text(render_python(spec), encoding="utf-8")
    KOTLIN_PATH.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_PATH.write_text(render_kotlin(spec), encoding="utf-8")
    print(f"Updated {PYTHON_PATH.relative_to(ROOT)}")
    print(f"Updated {KOTLIN_PATH.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
