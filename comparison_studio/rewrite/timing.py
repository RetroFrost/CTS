from __future__ import annotations

from dataclasses import dataclass

from .model import MODEL_ILLUSTRATED, Project

REVEAL_SECONDS = 2.0
REFERENCE_SCROLL_SECONDS = 10.0 / 3.0
ILLUSTRATED_SCROLL_SECONDS = 4.4
END_HOLD_SECONDS = 2.0
FADE_SECONDS = 0.8
MINIMUM_SCROLL_SECONDS = 1.0


@dataclass(frozen=True, slots=True)
class Placement:
    index: int
    x: float
    alpha: float
    badge_scale: float


class Timeline:
    """Pure timeline math shared by preview and export.

    Reveals, final hold, and fade always run at their authored speed. A custom total length
    stretches or compresses only the horizontal scrolling window. Badge geometry is stable:
    an optional entrance bounce may briefly grow above 100%, but badges never shrink.
    """

    def __init__(self, project: Project, card_count: int) -> None:
        self.project = project
        self.card_count = max(0, int(card_count))
        self.visible_cards = 4
        self.scroll_seconds = (
            ILLUSTRATED_SCROLL_SECONDS
            if project.model_id == MODEL_ILLUSTRATED
            else REFERENCE_SCROLL_SECONDS
        )

    @property
    def initial_count(self) -> int:
        return min(self.card_count, self.visible_cards)

    @property
    def reveal_duration(self) -> float:
        return self.initial_count * REVEAL_SECONDS

    @property
    def scroll_steps(self) -> int:
        return max(0, self.card_count - self.visible_cards)

    @property
    def automatic_scroll_duration(self) -> float:
        return self.scroll_steps * self.scroll_seconds

    @property
    def automatic_duration(self) -> float:
        if self.card_count <= 0:
            return 0.0
        return (
            self.reveal_duration
            + self.automatic_scroll_duration
            + END_HOLD_SECONDS
            + FADE_SECONDS
        )

    @property
    def minimum_duration(self) -> float:
        if self.card_count <= 0:
            return 1.0
        scroll = MINIMUM_SCROLL_SECONDS if self.scroll_steps else 0.0
        return self.reveal_duration + scroll + END_HOLD_SECONDS + FADE_SECONDS

    @property
    def output_duration(self) -> float:
        if self.project.custom_duration is None:
            return self.automatic_duration
        return max(self.minimum_duration, float(self.project.custom_duration))

    @property
    def output_scroll_duration(self) -> float:
        fixed = self.reveal_duration + END_HOLD_SECONDS + FADE_SECONDS
        return max(0.0, self.output_duration - fixed)

    @property
    def seconds_per_card(self) -> float:
        if self.scroll_steps <= 0:
            return 0.0
        return self.output_scroll_duration / self.scroll_steps

    def model_time(self, output_time: float) -> float:
        """Map output time to authored animation time without retiming reveals/endings."""
        output_time = max(0.0, float(output_time))
        if output_time >= self.output_duration:
            return self.automatic_duration
        if output_time <= self.reveal_duration:
            return output_time

        output_scroll_end = self.reveal_duration + self.output_scroll_duration
        if output_time < output_scroll_end:
            if self.output_scroll_duration <= 0 or self.automatic_scroll_duration <= 0:
                return self.reveal_duration
            progress = (output_time - self.reveal_duration) / self.output_scroll_duration
            return self.reveal_duration + progress * self.automatic_scroll_duration

        return (
            self.reveal_duration
            + self.automatic_scroll_duration
            + (output_time - output_scroll_end)
        )

    def fade_amount(self, output_time: float) -> float:
        fade_start = self.output_duration - FADE_SECONDS
        if output_time <= fade_start:
            return 0.0
        return smoothstep((output_time - fade_start) / FADE_SECONDS)

    def placements(self, output_time: float, viewport_width: float) -> list[Placement]:
        if self.card_count <= 0 or viewport_width <= 0:
            return []
        model_time = self.model_time(output_time)
        card_width = viewport_width / self.visible_cards
        placements: list[Placement] = []

        if model_time < self.reveal_duration:
            for index in range(self.initial_count):
                local = model_time - index * REVEAL_SECONDS
                if local < 0:
                    continue
                alpha = smoothstep(local / 0.62)
                scale = 1.0
                if self.project.badge_bounce:
                    overshoot = max(0.0, ease_out_back(local / 0.58) - 1.0)
                    scale += overshoot
                placements.append(Placement(index, index * card_width, alpha, scale))
            return placements

        scroll_elapsed = max(0.0, model_time - self.reveal_duration)
        shift_cards = min(
            float(self.scroll_steps),
            scroll_elapsed / max(0.001, self.scroll_seconds),
        )
        shift = shift_cards * card_width
        for index in range(self.card_count):
            x = index * card_width - shift
            if x >= viewport_width or x + card_width <= 0:
                continue
            placements.append(Placement(index, x, 1.0, 1.0))
        return placements


def clamp(value: float, minimum: float = 0.0, maximum: float = 1.0) -> float:
    return max(minimum, min(maximum, value))


def smoothstep(value: float) -> float:
    value = clamp(value)
    return value * value * (3.0 - 2.0 * value)


def ease_out_back(value: float) -> float:
    value = clamp(value)
    c1 = 1.70158
    c3 = c1 + 1
    return 1 + c3 * (value - 1) ** 3 + c1 * (value - 1) ** 2
