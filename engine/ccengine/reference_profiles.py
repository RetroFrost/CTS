from __future__ import annotations

from dataclasses import dataclass
from typing import Final

from .model_registry import MODEL_TYPES_OF_RELATIONSHIPS, MODEL_WHAT_MALES_LEARN, normalize_model_id


@dataclass(frozen=True, slots=True)
class LayoutProfile:
    slot_pitch: int
    body_inset: int
    body_width: int
    body_height: int
    image_height: int
    title_height: int
    description_top: int
    divider_width: int
    divider_color: tuple[int, int, int]
    title_background: tuple[int, int, int]
    description_background: tuple[int, int, int]
    badge_shape: str


@dataclass(frozen=True, slots=True)
class TimelineProfile:
    # Absolute source-frame addresses.  The first four cards do not share a
    # generic cadence in either canonical movie, so they must not be rebuilt
    # by repeatedly adding a rounded duration.
    opening_card_starts: tuple[int, int, int, int]
    opening_card_ends: tuple[int, int, int, int]
    continuous_start_frame: int
    continuous_step_frames: int
    continuous_tail_steps: int
    end_wipe_frames: int
    end_rise_frames: int
    end_hold_frames: int
    fade_frames: int
    black_tail_frames: int
    canonical_card_count: int

    @property
    def outro_frames(self) -> int:
        return (
            self.end_wipe_frames
            + self.end_rise_frames
            + self.end_hold_frames
            + self.fade_frames
            + self.black_tail_frames
        )

    def card_start_frame(self, index: int) -> int:
        if index < 0:
            raise IndexError(index)
        if index < 4:
            return self.opening_card_starts[index]
        return self.continuous_start_frame + (index - 4) * self.continuous_step_frames

    def content_end_frame(self, card_count: int) -> int:
        if card_count <= 0:
            return 0
        if card_count <= 4:
            return self.opening_card_ends[card_count - 1]
        return self.continuous_start_frame + (card_count - 4 + self.continuous_tail_steps) * self.continuous_step_frames


@dataclass(frozen=True, slots=True)
class ReferenceProfile:
    model_id: str
    layout: LayoutProfile
    timeline: TimelineProfile


# Integer frame/pixel contracts measured from the supplied 1920x1080/60 FPS
# files.  These are source addresses, not approximated seconds.
_PROFILES: Final[dict[str, ReferenceProfile]] = {
    MODEL_WHAT_MALES_LEARN: ReferenceProfile(
        model_id=MODEL_WHAT_MALES_LEARN,
        layout=LayoutProfile(
            slot_pitch=477,
            body_inset=9,
            body_width=471,
            body_height=924,
            image_height=750,
            title_height=80,
            description_top=830,
            divider_width=3,
            divider_color=(15, 15, 15),
            title_background=(247, 247, 245),
            description_background=(105, 102, 95),
            badge_shape="hexagon",
        ),
        timeline=TimelineProfile(
            opening_card_starts=(0, 120, 240, 360),
            # The four-card opening is followed by one source-only 55-frame
            # pause.  It is not repeated in each later conveyor step.
            opening_card_ends=(120, 240, 360, 535),
            continuous_start_frame=535,
            continuous_step_frames=214,
            continuous_tail_steps=0,
            # Frames 16371..16740 in the 78-card source.
            end_wipe_frames=10,
            end_rise_frames=29,
            end_hold_frames=270,
            fade_frames=61,
            black_tail_frames=0,
            canonical_card_count=78,
        ),
    ),
    MODEL_TYPES_OF_RELATIONSHIPS: ReferenceProfile(
        model_id=MODEL_TYPES_OF_RELATIONSHIPS,
        layout=LayoutProfile(
            slot_pitch=480,
            body_inset=0,
            body_width=475,
            body_height=1080,
            image_height=678,
            title_height=103,
            description_top=789,
            divider_width=5,
            divider_color=(234, 127, 28),
            title_background=(245, 245, 243),
            description_background=(47, 47, 47),
            badge_shape="octagon",
        ),
        timeline=TimelineProfile(
            opening_card_starts=(374, 521, 656, 795),
            opening_card_ends=(521, 656, 795, 896),
            continuous_start_frame=896,
            continuous_step_frames=266,
            continuous_tail_steps=1,
            # Frames 10738..11129 in the 41-card source.
            end_wipe_frames=42,
            end_rise_frames=50,
            end_hold_frames=270,
            fade_frames=30,
            black_tail_frames=0,
            canonical_card_count=40,
        ),
    ),
}


def get_reference_profile(model_id: object) -> ReferenceProfile:
    return _PROFILES[normalize_model_id(model_id)]
