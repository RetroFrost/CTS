from __future__ import annotations

"""CTS Easy timing policy generated from the shared Android-desktop contract.

A custom target duration changes only the horizontal card-scroll interval. Entrances,
the ending hold, and the fade keep their canonical durations on both platforms.
"""

from dataclasses import asdict
from typing import Any, TypeVar

from .data import ProjectSettings
from .shared_contract import (
    MIN_SCROLL_STEP_SECONDS,
    SCROLL_SECONDS,
    VISIBLE_CARDS,
    automatic_duration,
    chosen_duration,
    model_time as shared_model_time,
    scroll_seconds_per_card as shared_scroll_seconds_per_card,
    timeline_parts as shared_timeline_parts,
)

SettingsT = TypeVar("SettingsT", bound=ProjectSettings)
_EASY_SETTINGS_TYPES: dict[type, type] = {}


def timeline_parts(_settings: ProjectSettings, card_count: int) -> tuple[float, int, float, float]:
    """Return intro seconds, scroll step count, automatic scroll seconds, and fixed tail."""
    return shared_timeline_parts(card_count)


def minimum_duration(settings: ProjectSettings, card_count: int) -> float:
    """Shortest safe target while retaining a visible movement for every scroll step."""
    intro, scroll_steps, _automatic_scroll, fixed_tail = timeline_parts(settings, card_count)
    if scroll_steps <= 0:
        return automatic_duration(card_count)
    return intro + scroll_steps * MIN_SCROLL_STEP_SECONDS + fixed_tail


class EasyTimingMixin:
    """Override timing only on settings returned by the CTS Easy window."""

    def effective_visible_cards(self) -> int:
        return VISIBLE_CARDS

    def auto_duration(self, card_count: int) -> float:
        return automatic_duration(card_count)

    def duration(self, card_count: int) -> float:
        return chosen_duration(card_count, self.custom_duration)

    def speed_multiplier(self, card_count: int) -> float:
        """Compatibility value for the horizontal scroll segment only."""
        _intro, scroll_steps, automatic_scroll, _fixed_tail = timeline_parts(self, card_count)
        if scroll_steps <= 0 or automatic_scroll <= 0.0:
            return 1.0
        chosen_scroll = self.seconds_per_card(card_count) * scroll_steps
        return automatic_scroll / max(0.001, chosen_scroll)

    def model_time(self, output_time: float, card_count: int) -> float:
        return shared_model_time(card_count, output_time, self.custom_duration)

    def seconds_per_card(self, card_count: int) -> float:
        return shared_scroll_seconds_per_card(card_count, self.custom_duration)


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
    """Copy any CTS settings dataclass into the shared-contract runtime type."""
    if isinstance(settings, EasyTimingMixin):
        return settings
    easy_type = _easy_settings_type(type(settings))
    values: dict[str, Any] = asdict(settings)
    return easy_type(**values)
