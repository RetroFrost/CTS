from __future__ import annotations

"""CTS Easy timing policy generated from the shared Android-desktop contract.

A custom target duration scales the complete animation exactly as Android does. Entrances,
scrolling, the ending hold, and the fade therefore remain proportionally identical on both
platforms instead of drifting into separate timing implementations.
"""

from dataclasses import asdict
from typing import Any, TypeVar

from .data import ProjectSettings
from .shared_contract import (
    END_HOLD_SECONDS,
    FADE_SECONDS,
    INTRO_TAIL_HOLD_SECONDS,
    REVEAL_SECONDS,
    SCROLL_SECONDS,
    VISIBLE_CARDS,
    automatic_duration,
    chosen_duration,
    model_time as shared_model_time,
)

SettingsT = TypeVar("SettingsT", bound=ProjectSettings)
_EASY_SETTINGS_TYPES: dict[type, type] = {}


def timeline_parts(settings: ProjectSettings, card_count: int) -> tuple[float, int, float, float]:
    """Return intro seconds, scroll steps, automatic scroll seconds, and fixed tail."""
    if card_count <= 0:
        return 0.0, 0, 0.0, 0.0
    intro = min(card_count, VISIBLE_CARDS) * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
    scroll_steps = max(0, card_count - VISIBLE_CARDS)
    automatic_scroll = scroll_steps * SCROLL_SECONDS
    fixed_tail = END_HOLD_SECONDS + FADE_SECONDS
    return intro, scroll_steps, automatic_scroll, fixed_tail


def minimum_duration(_settings: ProjectSettings, card_count: int) -> float:
    """Android accepts any custom duration of at least one second."""
    return 0.0 if card_count <= 0 else 1.0


class EasyTimingMixin:
    """Override timing only on settings returned by the CTS Easy window."""

    def effective_visible_cards(self) -> int:
        return VISIBLE_CARDS

    def auto_duration(self, card_count: int) -> float:
        return automatic_duration(card_count)

    def duration(self, card_count: int) -> float:
        return chosen_duration(card_count, self.custom_duration)

    def speed_multiplier(self, card_count: int) -> float:
        automatic = self.auto_duration(card_count)
        chosen = self.duration(card_count)
        if automatic <= 0.0 or chosen <= 0.0:
            return 1.0
        return automatic / chosen

    def model_time(self, output_time: float, card_count: int) -> float:
        return shared_model_time(card_count, output_time, self.custom_duration)

    def seconds_per_card(self, card_count: int) -> float:
        if card_count <= VISIBLE_CARDS:
            return 0.0
        speed = self.speed_multiplier(card_count)
        return SCROLL_SECONDS / speed if speed > 0.0 else 0.0


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
