from __future__ import annotations

"""Generated from shared/cts_contract.json. Do not edit by hand."""

from dataclasses import dataclass
from math import floor

CONTRACT_VERSION = 1
PROJECT_VERSION = 3
MODEL_ID = "illustrated_cards"
MODEL_LABEL = "Reference Timeline"
VISIBLE_CARDS = 4
LEGACY_MODEL_IDS = ("illustrated_cards", "reference_detail", "classic_compact")
FIELDS = ("badge_primary", "badge_secondary", "title", "description", "image")

REVEAL_SECONDS = 2.0
SCROLL_SECONDS = 3.3333333333333335
END_HOLD_SECONDS = 2.0
FADE_SECONDS = 0.8
BODY_WIPE_SECONDS = 1.1
BADGE_DELAY_SECONDS = 0.55
BADGE_SETTLE_SECONDS = 2.6
INTRO_TAIL_HOLD_SECONDS = 0.8
MIN_SCROLL_STEP_SECONDS = 0.12
MATERIAL_EASE = (0.4, 0.0, 0.2, 1.0)

IMAGE_FRAME = (0.008, 0.0, 0.984, 0.807)
TITLE_FRAME = (0.008, 0.807, 0.984, 0.088)
DESCRIPTION_FRAME = (0.008, 0.895, 0.984, 0.101)
BADGE_FRAME = (0.245, 0.063, 0.51, 0.263)

COLORS = {
    "background": "#000000",
    "image_top": "#138DDB",
    "image_bottom": "#0B74BE",
    "title_background": "#F0F0F0",
    "title_text": "#101010",
    "description_background": "#625F56",
    "description_text": "#FFFFFF",
    "divider": "#11100C",
    "badge_top": "#EB0909",
    "badge_middle": "#E00000",
    "badge_bottom": "#D50000",
    "badge_border": "#FF4545",
}


@dataclass(frozen=True, slots=True)
class SharedCard:
    badge_primary: str
    badge_secondary: str
    title: str
    description: str


SAMPLE_CARDS = (
    SharedCard("10", "SECONDS OLD", "Breathing", "A baby's first breath requires blood flow through the heart."),
    SharedCard("1", "HOUR OLD", "Suckling", "Newborns instinctively try to feed within just hours."),
    SharedCard("3", "DAYS OLD", "Recognizing Mom's Smell", "Within days a baby can recognize a familiar scent."),
    SharedCard("6.5", "MONTHS OLD", "Recognizing Their Own Name", "A baby turns toward their name months before speaking."),
    SharedCard("8", "MONTHS OLD", "Object Permanence", "Objects still exist even when they are out of sight."),
)


def normalize_model_id(_value: str | None) -> str:
    return MODEL_ID


def automatic_duration(card_count: int) -> float:
    if card_count <= 0:
        return 0.0
    reveal = min(card_count, VISIBLE_CARDS) * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
    scroll = max(0, card_count - VISIBLE_CARDS) * SCROLL_SECONDS
    return reveal + scroll + END_HOLD_SECONDS + FADE_SECONDS


def timeline_parts(card_count: int) -> tuple[float, int, float, float]:
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
