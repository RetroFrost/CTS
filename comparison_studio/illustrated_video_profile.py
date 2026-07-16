from __future__ import annotations

"""Reference-video timing and schema for the Illustrated Cards desktop model.

The original comparison video uses four narrow cards and a slower strip than the other
CTS layouts. Custom video length changes only the scrolling window: reveal, ending hold,
and fade timings remain fixed.
"""

from . import data as data_module

ILLUSTRATED_VISIBLE_CARDS = 4
ILLUSTRATED_SCROLL_SECONDS = 4.4
MINIMUM_SCROLL_WINDOW_SECONDS = 1.0

_INSTALLED = False


def _base_scroll_seconds(settings) -> float:
    if settings.model_id == data_module.MODEL_ILLUSTRATED:
        return ILLUSTRATED_SCROLL_SECONDS
    return data_module.REFERENCE_SCROLL_SECONDS


def _reveal_duration(settings, card_count: int) -> float:
    if card_count <= 0:
        return 0.0
    return min(card_count, settings.effective_visible_cards()) * data_module.REFERENCE_REVEAL_SECONDS


def _scroll_steps(settings, card_count: int) -> int:
    return max(0, card_count - settings.effective_visible_cards())


def _automatic_scroll_duration(settings, card_count: int) -> float:
    return _scroll_steps(settings, card_count) * _base_scroll_seconds(settings)


def _minimum_duration(settings, card_count: int) -> float:
    if card_count <= 0:
        return 1.0
    scroll_window = MINIMUM_SCROLL_WINDOW_SECONDS if _scroll_steps(settings, card_count) else 0.0
    return (
        _reveal_duration(settings, card_count)
        + scroll_window
        + data_module.REFERENCE_END_HOLD_SECONDS
        + data_module.REFERENCE_FADE_SECONDS
    )


def _auto_duration(settings, card_count: int) -> float:
    if card_count <= 0:
        return 0.0
    return (
        _reveal_duration(settings, card_count)
        + _automatic_scroll_duration(settings, card_count)
        + data_module.REFERENCE_END_HOLD_SECONDS
        + data_module.REFERENCE_FADE_SECONDS
    )


def _duration(settings, card_count: int) -> float:
    automatic = _auto_duration(settings, card_count)
    if settings.custom_duration is None:
        return automatic
    return max(_minimum_duration(settings, card_count), float(settings.custom_duration))


def _chosen_scroll_duration(settings, card_count: int) -> float:
    chosen = _duration(settings, card_count)
    fixed = (
        _reveal_duration(settings, card_count)
        + data_module.REFERENCE_END_HOLD_SECONDS
        + data_module.REFERENCE_FADE_SECONDS
    )
    return max(0.0, chosen - fixed)


def _model_time(settings, output_time: float, card_count: int) -> float:
    """Map output time onto the model timeline without retiming card reveals."""
    output_time = max(0.0, float(output_time))
    chosen_duration = _duration(settings, card_count)
    automatic_duration = _auto_duration(settings, card_count)
    if output_time >= chosen_duration:
        return automatic_duration

    reveal = _reveal_duration(settings, card_count)
    if output_time <= reveal:
        return output_time

    automatic_scroll = _automatic_scroll_duration(settings, card_count)
    chosen_scroll = _chosen_scroll_duration(settings, card_count)
    scroll_end = reveal + chosen_scroll
    if output_time < scroll_end:
        if automatic_scroll <= 0.0 or chosen_scroll <= 0.0:
            return reveal
        return reveal + (output_time - reveal) * (automatic_scroll / chosen_scroll)

    # Ending hold and fade remain one-to-one with output time.
    return reveal + automatic_scroll + (output_time - scroll_end)


def _speed_multiplier(settings, card_count: int) -> float:
    automatic_scroll = _automatic_scroll_duration(settings, card_count)
    chosen_scroll = _chosen_scroll_duration(settings, card_count)
    if automatic_scroll <= 0.0 or chosen_scroll <= 0.0:
        return 1.0
    return automatic_scroll / chosen_scroll


def _seconds_per_card(settings, card_count: int) -> float:
    steps = _scroll_steps(settings, card_count)
    if steps <= 0:
        return 0.0
    return _chosen_scroll_duration(settings, card_count) / steps


def install_illustrated_video_profile() -> None:
    """Install the measured Illustrated Cards profile once for desktop CTS."""
    global _INSTALLED
    if _INSTALLED:
        return

    data_module.MODEL_DEFAULT_VISIBLE[data_module.MODEL_ILLUSTRATED] = ILLUSTRATED_VISIBLE_CARDS
    data_module.MODEL_SCHEMAS[data_module.MODEL_ILLUSTRATED] = (
        ("Badge Value", "badge_primary"),
        ("Badge Label", "badge_secondary"),
        ("Title", "title"),
        ("Description", "description"),
        ("Artwork", "image"),
    )

    settings_type = data_module.ProjectSettings
    settings_type.reveal_duration = _reveal_duration
    settings_type.scroll_steps = _scroll_steps
    settings_type.base_scroll_seconds = _base_scroll_seconds
    settings_type.automatic_scroll_duration = _automatic_scroll_duration
    settings_type.minimum_duration = _minimum_duration
    settings_type.auto_duration = _auto_duration
    settings_type.duration = _duration
    settings_type.model_time = _model_time
    settings_type.speed_multiplier = _speed_multiplier
    settings_type.seconds_per_card = _seconds_per_card

    _INSTALLED = True
