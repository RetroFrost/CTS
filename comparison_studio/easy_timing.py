from __future__ import annotations

"""CTS Easy timing policy.

A target video length changes only the horizontal card-scroll interval. Card entrances,
the final hold, and the fade keep their normal durations, so a shorter video feels faster
without looking like the entire animation was time-stretched.
"""

from dataclasses import asdict
from typing import Any, TypeVar

from .data import (
    REFERENCE_END_HOLD_SECONDS,
    REFERENCE_FADE_SECONDS,
    REFERENCE_REVEAL_SECONDS,
    REFERENCE_SCROLL_SECONDS,
    ProjectSettings,
)

MIN_SCROLL_STEP_SECONDS = 0.12
SettingsT = TypeVar("SettingsT", bound=ProjectSettings)
_EASY_SETTINGS_TYPES: dict[type, type] = {}


def timeline_parts(settings: ProjectSettings, card_count: int) -> tuple[float, int, float, float]:
    """Return intro seconds, scroll step count, automatic scroll seconds, and fixed tail."""
    if card_count <= 0:
        return 0.0, 0, 0.0, 0.0
    visible = settings.effective_visible_cards()
    intro = min(card_count, visible) * REFERENCE_REVEAL_SECONDS
    scroll_steps = max(0, card_count - visible)
    automatic_scroll = scroll_steps * REFERENCE_SCROLL_SECONDS
    fixed_tail = REFERENCE_END_HOLD_SECONDS + REFERENCE_FADE_SECONDS
    return intro, scroll_steps, automatic_scroll, fixed_tail


def minimum_duration(settings: ProjectSettings, card_count: int) -> float:
    """Shortest safe target while preserving entrances, hold, and fade."""
    intro, scroll_steps, _automatic_scroll, fixed_tail = timeline_parts(settings, card_count)
    return intro + scroll_steps * MIN_SCROLL_STEP_SECONDS + fixed_tail


class EasyTimingMixin:
    """Override timing only on settings returned by the CTS Easy window."""

    def duration(self, card_count: int) -> float:
        automatic = self.auto_duration(card_count)
        if self.custom_duration is None or card_count <= 0:
            return automatic

        _intro, scroll_steps, _automatic_scroll, _fixed_tail = timeline_parts(self, card_count)
        # With no off-screen cards there is no horizontal scrolling to retime.
        if scroll_steps <= 0:
            return automatic
        return max(minimum_duration(self, card_count), float(self.custom_duration))

    def speed_multiplier(self, card_count: int) -> float:
        """Compatibility value: only the horizontal scroll segment uses this factor."""
        if self.custom_duration is None:
            return 1.0
        intro, scroll_steps, automatic_scroll, fixed_tail = timeline_parts(self, card_count)
        if scroll_steps <= 0 or automatic_scroll <= 0:
            return 1.0
        chosen_scroll = max(
            scroll_steps * MIN_SCROLL_STEP_SECONDS,
            self.duration(card_count) - intro - fixed_tail,
        )
        return automatic_scroll / max(0.001, chosen_scroll)

    def model_time(self, output_time: float, card_count: int) -> float:
        """Map output time into the original model timeline without scaling entrances/tail."""
        output_time = max(0.0, float(output_time))
        if self.custom_duration is None:
            return output_time

        intro, scroll_steps, automatic_scroll, fixed_tail = timeline_parts(self, card_count)
        if scroll_steps <= 0 or automatic_scroll <= 0:
            return output_time
        if output_time <= intro:
            return output_time

        chosen_scroll = max(
            scroll_steps * MIN_SCROLL_STEP_SECONDS,
            self.duration(card_count) - intro - fixed_tail,
        )
        if output_time < intro + chosen_scroll:
            scroll_progress = (output_time - intro) / max(0.001, chosen_scroll)
            return intro + scroll_progress * automatic_scroll

        # Once scrolling finishes, the normal hold and fade proceed at real-time speed.
        return intro + automatic_scroll + (output_time - intro - chosen_scroll)

    def seconds_per_card(self, card_count: int) -> float:
        intro, scroll_steps, _automatic_scroll, fixed_tail = timeline_parts(self, card_count)
        if scroll_steps <= 0:
            return 0.0
        if self.custom_duration is None:
            return REFERENCE_SCROLL_SECONDS
        chosen_scroll = max(
            scroll_steps * MIN_SCROLL_STEP_SECONDS,
            self.duration(card_count) - intro - fixed_tail,
        )
        return chosen_scroll / scroll_steps


def _easy_settings_type(base_type: type) -> type:
    cached = _EASY_SETTINGS_TYPES.get(base_type)
    if cached is not None:
        return cached
    easy_type = type(
        f"Easy{base_type.__name__}",
        (EasyTimingMixin, base_type),
        {"__slots__": (), "__module__": __name__},
    )
    _EASY_SETTINGS_TYPES[base_type] = easy_type
    return easy_type


def with_easy_timing(settings: SettingsT) -> SettingsT:
    """Copy any CTS settings dataclass into an equivalent Easy-timed runtime type.

    This preserves later visual/transform settings subclasses and, unlike a global
    monkeypatch, leaves the established renderer and timing unit tests untouched.
    """
    if isinstance(settings, EasyTimingMixin):
        return settings
    easy_type = _easy_settings_type(type(settings))
    values: dict[str, Any] = asdict(settings)
    return easy_type(**values)
