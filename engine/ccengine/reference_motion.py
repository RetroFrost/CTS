from __future__ import annotations

from bisect import bisect_right

from .model_registry import MODEL_TYPES_OF_RELATIONSHIPS, MODEL_WHAT_MALES_LEARN, normalize_model_id
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

    Both references become linear conveyors after the opening, but the Age
    source has a one-time phase pull during the opening->middle hand-off.
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
    position_step_frames = (
        214.34022763049293 if normalized == MODEL_WHAT_MALES_LEARN
        else 265.7158647968422
    )
    shift_slots = local / position_step_frames

    if normalized == MODEL_WHAT_MALES_LEARN:
        # Measured separator correction (pixels) relative to the linear
        # 477 px / 214 frame conveyor.  It is a single transition pull, not a
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



_RELATIONSHIPS_BADGE_BBOX = {
    11: (208.0, 168.0, 48.0, 48.0),
    12: (192.0, 152.0, 80.0, 80.0),
    13: (176.0, 136.0, 112.0, 112.0),
    14: (160.0, 120.0, 144.0, 144.0),
    15: (144.0, 104.0, 176.0, 176.0),
    16: (128.0, 92.0, 208.0, 196.0),
    17: (112.0, 80.0, 240.0, 224.0),
    18: (104.0, 64.0, 256.0, 256.0),
    19: (88.0, 48.0, 288.0, 288.0),
    20: (72.0, 40.0, 320.0, 304.0),
    21: (64.0, 24.0, 336.0, 336.0),
    22: (48.0, 16.0, 360.0, 352.0),
    23: (40.0, 8.0, 384.0, 368.0),
    24: (32.0, 0.0, 400.0, 384.0),
    25: (24.0, 0.0, 416.0, 392.0),
    26: (24.0, 0.0, 416.0, 400.0),
    27: (16.0, 0.0, 432.0, 400.0),
    28: (16.0, 0.0, 424.0, 400.0),
    29: (24.0, 0.0, 416.0, 392.0),
    30: (32.0, 0.0, 400.0, 384.0),
    31: (40.0, 8.0, 376.0, 368.0),
    32: (48.0, 16.0, 368.0, 352.0),
    33: (56.0, 24.0, 352.0, 336.0),
    34: (64.0, 24.0, 336.0, 336.0),
    35: (64.0, 32.0, 336.0, 320.0),
    36: (72.0, 32.0, 320.0, 320.0),
    37: (72.0, 32.0, 320.0, 320.0),
    38: (64.0, 32.0, 336.0, 320.0),
    39: (64.0, 24.0, 336.0, 336.0),
    40: (56.0, 24.0, 352.0, 336.0),
    41: (56.0, 16.0, 352.0, 352.0),
    42: (48.0, 16.0, 368.0, 352.0),
    43: (48.0, 8.0, 368.0, 368.0),
    44: (48.0, 8.0, 368.0, 368.0),
    45: (48.0, 8.0, 368.0, 368.0),
    46: (48.0, 8.0, 368.0, 368.0),
    47: (48.0, 8.0, 368.0, 360.0),
    48: (48.0, 16.0, 368.0, 352.0),
    49: (56.0, 16.0, 360.0, 352.0),
    50: (56.0, 16.0, 352.0, 352.0),
    51: (56.0, 16.0, 352.0, 352.0),
    52: (56.0, 16.0, 352.0, 352.0),
    53: (56.0, 16.0, 352.0, 352.0),
    54: (56.0, 16.0, 352.0, 352.0),
    55: (56.0, 16.0, 352.0, 352.0),
    56: (56.0, 16.0, 352.0, 352.0),
    57: (56.0, 16.0, 352.0, 352.0),
    58: (56.0, 16.0, 352.0, 352.0),
    59: (56.0, 16.0, 352.0, 352.0),
    60: (56.0, 20.0, 352.0, 348.0),
    61: (56.0, 20.0, 352.0, 348.0),
}

def relationships_badge_bbox(local_frame: int) -> tuple[float, float, float, float] | None:
    frame = int(local_frame)
    if frame < 11:
        return None
    if frame >= 61:
        return _RELATIONSHIPS_BADGE_BBOX[61]
    return _RELATIONSHIPS_BADGE_BBOX[frame]

# Relationships opening shell scale, measured every frame from the first red
# component in source frames 385..424.  The source is a pure centre-anchored
# scale bounce: grow, overshoot, undershoot, second overshoot, settle.
_RELATIONSHIPS_BADGE_SCALE_KEYS = (
    (10, 0.0),
    (11, 12 / 88),
    (15, 44 / 88),
    (19, 72 / 88),
    (23, 96 / 88),
    (27, 108 / 88),
    (30, 100 / 88),
    (33, 88 / 88),
    (36, 80 / 88),
    (40, 88 / 88),
    (44, 92 / 88),
    (48, 92 / 88),
    (50, 88 / 88),
    (60, 1.0),
)


def relationships_badge_scale(local_frame: int) -> float:
    return _sample(_RELATIONSHIPS_BADGE_SCALE_KEYS, int(local_frame))


def relationships_shell_visible(local_frame: int) -> bool:
    return int(local_frame) >= 11


def relationships_artwork_reveal(local_frame: int) -> float:
    # Source: first artwork pixels at local frame ~60, complete by ~101.
    return max(0.0, min(1.0, (int(local_frame) - 58) / 43.0))


def relationships_title_reveal(local_frame: int) -> float:
    return max(0.0, min(1.0, (int(local_frame) - 96) / 10.0))


def relationships_description_reveal(local_frame: int) -> float:
    return max(0.0, min(1.0, (int(local_frame) - 105) / 15.0))


def relationships_badge_text_age(local_frame: int) -> float:
    # Keep the shell final while the source types/fades the value late in the
    # reveal.  The renderer's canonical text clock uses 0.9..2.3.
    progress = max(0.0, min(1.0, (int(local_frame) - 88) / 32.0))
    return 0.9 + progress * 1.4


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
