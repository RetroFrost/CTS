from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected block not found in {path}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


spec_path = ROOT / "shared/cts_contract.json"
spec = json.loads(spec_path.read_text(encoding="utf-8"))
spec["timing"]["min_scroll_step_seconds"] = 0.12
spec_path.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

sync = "tools/sync_shared_contract.py"
replace_once(
    sync,
    '    "INTRO_TAIL_HOLD_SECONDS": "intro_tail_hold_seconds",\n',
    '    "INTRO_TAIL_HOLD_SECONDS": "intro_tail_hold_seconds",\n    "MIN_SCROLL_STEP_SECONDS": "min_scroll_step_seconds",\n',
)
replace_once(
    sync,
    'INTRO_TAIL_HOLD_SECONDS = {float(timing["intro_tail_hold_seconds"])}\nMATERIAL_EASE = ({ease})\n',
    'INTRO_TAIL_HOLD_SECONDS = {float(timing["intro_tail_hold_seconds"])}\nMIN_SCROLL_STEP_SECONDS = {float(timing["min_scroll_step_seconds"])}\nMATERIAL_EASE = ({ease})\n',
)
replace_once(
    sync,
    r'''def chosen_duration(card_count: int, custom_duration: float | None) -> float:
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
''',
    r'''def timeline_parts(card_count: int) -> tuple[float, int, float, float]:
    if card_count <= 0:
        return 0.0, 0, 0.0, 0.0
    intro = min(card_count, VISIBLE_CARDS) * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
    scroll_steps = max(0, card_count - VISIBLE_CARDS)
    automatic_scroll = scroll_steps * SCROLL_SECONDS
    fixed_tail = END_HOLD_SECONDS + FADE_SECONDS
    return intro, scroll_steps, automatic_scroll, fixed_tail


def chosen_duration(card_count: int, custom_duration: float | None) -> float:
    automatic = automatic_duration(card_count)
    intro, scroll_steps, _automatic_scroll, fixed_tail = timeline_parts(card_count)
    if custom_duration is None or scroll_steps <= 0:
        return automatic
    minimum = intro + scroll_steps * MIN_SCROLL_STEP_SECONDS + fixed_tail
    return max(minimum, float(custom_duration))


def scroll_seconds_per_card(card_count: int, custom_duration: float | None) -> float:
    intro, scroll_steps, automatic_scroll, fixed_tail = timeline_parts(card_count)
    if scroll_steps <= 0:
        return 0.0
    if custom_duration is None:
        return SCROLL_SECONDS
    chosen_scroll = max(
        scroll_steps * MIN_SCROLL_STEP_SECONDS,
        chosen_duration(card_count, custom_duration) - intro - fixed_tail,
    )
    return chosen_scroll / scroll_steps


def model_time(card_count: int, output_time: float, custom_duration: float | None) -> float:
    output = max(0.0, float(output_time))
    intro, scroll_steps, automatic_scroll, fixed_tail = timeline_parts(card_count)
    if custom_duration is None or scroll_steps <= 0 or automatic_scroll <= 0.0:
        return output
    if output <= intro:
        return output
    chosen_scroll = max(
        scroll_steps * MIN_SCROLL_STEP_SECONDS,
        chosen_duration(card_count, custom_duration) - intro - fixed_tail,
    )
    if output < intro + chosen_scroll:
        return intro + ((output - intro) / max(0.001, chosen_scroll)) * automatic_scroll
    return intro + automatic_scroll + (output - intro - chosen_scroll)


def output_time_for_model_time(card_count: int, model_time_value: float, custom_duration: float | None) -> float:
    model_value = max(0.0, float(model_time_value))
    intro, scroll_steps, automatic_scroll, fixed_tail = timeline_parts(card_count)
    if custom_duration is None or scroll_steps <= 0 or automatic_scroll <= 0.0:
        return model_value
    if model_value <= intro:
        return model_value
    chosen_scroll = max(
        scroll_steps * MIN_SCROLL_STEP_SECONDS,
        chosen_duration(card_count, custom_duration) - intro - fixed_tail,
    )
    if model_value < intro + automatic_scroll:
        return intro + ((model_value - intro) / max(0.001, automatic_scroll)) * chosen_scroll
    return intro + chosen_scroll + (model_value - intro - automatic_scroll)


def editing_time_for_card(card_count: int, card_index: int, custom_duration: float | None) -> float:
    if card_count <= 0:
        return 0.0
    safe_index = max(0, min(card_index, card_count - 1))
    initial_count = min(card_count, VISIBLE_CARDS)
    scroll_start = initial_count * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
    target_model_time = scroll_start if safe_index < VISIBLE_CARDS else scroll_start + (safe_index - VISIBLE_CARDS + 1) * SCROLL_SECONDS
    return min(
        chosen_duration(card_count, custom_duration),
        output_time_for_model_time(card_count, target_model_time, custom_duration),
    )
''',
)
replace_once(
    sync,
    '    const val INTRO_TAIL_HOLD_SECONDS = {float(timing["intro_tail_hold_seconds"])}f\n\n    const val MATERIAL_EASE_X1',
    '    const val INTRO_TAIL_HOLD_SECONDS = {float(timing["intro_tail_hold_seconds"])}f\n    const val MIN_SCROLL_STEP_SECONDS = {float(timing["min_scroll_step_seconds"])}f\n\n    const val MATERIAL_EASE_X1',
)

replace_once(
    "android/app/src/main/java/io/github/retrofrost/cts/android/timeline/TimelineEngine.kt",
    "private const val MIN_SCROLL_STEP_SECONDS = 0.12f",
    "const val MIN_SCROLL_STEP_SECONDS = SharedContract.MIN_SCROLL_STEP_SECONDS",
)

layout = "android/app/src/main/java/io/github/retrofrost/cts/android/layout/CardContentLayout.kt"
replace_once(
    layout,
    "import io.github.retrofrost.cts.android.model.CtsCard\n",
    "import io.github.retrofrost.cts.android.model.CtsCard\nimport io.github.retrofrost.cts.android.shared.SharedContract\n",
)
replace_once(
    layout,
    r'''    private const val LEFT = 0.008f
    private const val WIDTH = 0.984f
    private const val CONTENT_BOTTOM = 0.996f
    private const val TITLE_HEIGHT = 0.088f
    private const val DESCRIPTION_HEIGHT = 0.101f
''',
    r'''    private const val LEFT = SharedContract.IMAGE_X
    private const val WIDTH = SharedContract.IMAGE_WIDTH
    private const val CONTENT_BOTTOM = SharedContract.DESCRIPTION_Y + SharedContract.DESCRIPTION_HEIGHT
    private const val TITLE_HEIGHT = SharedContract.TITLE_HEIGHT
    private const val DESCRIPTION_HEIGHT = SharedContract.DESCRIPTION_HEIGHT
''',
)

replace_once(
    "android/app/src/main/java/io/github/retrofrost/cts/android/ui/ProgramMonitor.kt",
    "private val BadgeFrame = NormalizedRect(0.245f, 0.063f, 0.51f, 0.263f)\n",
    "private val ImageFrame = NormalizedRect(0.008f, 0f, 0.984f, 0.807f)\nprivate val TitleFrame = NormalizedRect(0.008f, 0.807f, 0.984f, 0.088f)\nprivate val DescriptionFrame = NormalizedRect(0.008f, 0.895f, 0.984f, 0.101f)\nprivate val BadgeFrame = NormalizedRect(0.245f, 0.063f, 0.51f, 0.263f)\n",
)

replace_once(
    "tests/test_shared_contract.py",
    r'''        self.assertAlmostEqual(shared_contract.chosen_duration(5, 7.5), 7.5)
        self.assertAlmostEqual(shared_contract.model_time(5, 3.75, 7.5), expected / 2.0)
''',
    r'''        custom = expected + 6.0
        scroll_start = 4 * 2.0 + 0.8
        self.assertAlmostEqual(shared_contract.chosen_duration(5, custom), custom)
        self.assertAlmostEqual(
            shared_contract.scroll_seconds_per_card(5, custom),
            (10.0 / 3.0) + 6.0,
        )
        self.assertAlmostEqual(shared_contract.model_time(5, scroll_start, custom), scroll_start)
        self.assertAlmostEqual(
            shared_contract.model_time(
                5,
                scroll_start + shared_contract.scroll_seconds_per_card(5, custom) / 2.0,
                custom,
            ),
            scroll_start + (10.0 / 3.0) / 2.0,
        )
''',
)

subprocess.run([sys.executable, "tools/sync_shared_contract.py"], cwd=ROOT, check=True)
print("Applied shared segment-only scrolling contract")
