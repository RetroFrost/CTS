from __future__ import annotations

from bisect import bisect_right

from .model_registry import MODEL_WHAT_MALES_LEARN, normalize_model_id
from .exact_reference_frames import continuous_card_x
from .reference_profiles import get_reference_profile


def _lerp(start: float, end: float, amount: float) -> float:
    return start + (end - start) * amount


def _sample(keys: tuple[tuple[int, float], ...], frame: int) -> float:
    frame = int(frame)
    if frame <= keys[0][0]:
        return keys[0][1]
    if frame >= keys[-1][0]:
        return keys[-1][1]
    positions = [item[0] for item in keys]
    right = bisect_right(positions, frame)
    f0, v0 = keys[right - 1]
    f1, v1 = keys[right]
    return _lerp(v0, v1, (frame - f0) / (f1 - f0))


def continuous_shift(model_id: object, global_frame: int) -> float:
    """Continuous source-strip displacement, in card slots.

    The Males source becomes a linear conveyor after the opening, with a
    one-time phase pull during the opening-to-middle hand-off.
    Frame measurements show the strip gains ~91 px during frames 535..620;
    omitting that pull leaves every later card visibly out of phase even when
    the long-run speed is correct.
    """
    profile = get_reference_profile(model_id)
    timeline = profile.timeline
    frame = int(global_frame)
    if frame < timeline.continuous_start_frame:
        return 0.0
    exact_x = continuous_card_x(model_id, frame, 0)
    if exact_x is not None:
        return -exact_x / profile.layout.slot_pitch
    local = frame - timeline.continuous_start_frame
    normalized = normalize_model_id(model_id)
    # Separator tracks measured over the continuous sections.  These are
    # positional clocks; badge/card event cadence remains on the integer
    # timeline frames in reference_profiles.py.
    position_step_frames = 214.34022763049293
    shift_slots = local / position_step_frames

    if normalized == MODEL_WHAT_MALES_LEARN:
        # Measured separator correction (pixels) relative to the linear
        # 476 px / 214 frame conveyor.  It is a single transition pull, not a
        # repeated per-card easing.
        correction_keys = (
            (0, 9.5),
            (15, 7.0),
            (25, 13.0),
            (35, 23.5),
            (45, 36.2),
            (55, 52.9),
            (65, 70.6),
            (75, 87.3),
            (85, 101.0),
            (95, 100.7),
        )
        correction_px = _sample(correction_keys, min(local, 95))
        shift_slots += correction_px / profile.layout.slot_pitch

    return shift_slots

def age_opening_badge_age(local_frame: int) -> float:
    # First red pixels at frame 35; final source polygon at frame 120.
    progress = max(0.0, min(1.0, (int(local_frame) - 35) / 85.0))
    return progress * 2.9


def age_later_badge_age(local_frame: int) -> float:
    # Later badges start 122 frames after a conveyor step and settle over 103
    # frames, measured from the canonical continuous section.
    progress = (int(local_frame) - 122) / 103.0
    return progress * 2.25


def conveyor_progress(model_id: object, local_frame: int) -> float:
    """Compatibility helper for callers that need one source step."""
    profile = get_reference_profile(model_id).timeline
    return max(0.0, min(1.0, int(local_frame) / profile.continuous_step_frames))
