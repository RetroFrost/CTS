from __future__ import annotations

"""Compatibility API backed by the Cubical Compare Canary frame tables."""

import base64
import zlib
from functools import lru_cache

from .canary_reference import (
    CONTINUOUS_START_FRAME,
    CONTENT_END_FRAME,
    SLOT_PITCH,
    BODY_WIDTH,
    continuous_body_x,
)
from .model_registry import MODEL_WHAT_MALES_LEARN, normalize_model_id

MALES_CONVEYOR_START = CONTINUOUS_START_FRAME
MALES_CONVEYOR_END = CONTENT_END_FRAME
MALES_CANONICAL_CONVEYOR_END = CONTENT_END_FRAME
MALES_CARD_PITCH_PX = float(SLOT_PITCH)
MALES_CARD_WIDTH_PX = float(BODY_WIDTH)
MALES_FADE_START = 12_180
MALES_FADE_END = 12_258

_FADE = "eNoBTwCw//7+/Pv49fTz8e7s6+nm5ODc2tfT0MzJxsO/vbi1s6+sqaWioJyZl5KQjYqHhHt7enh1c25raWViYF1ZV1NPTkpHREE3NzUzLiwoIx8ZEgD7nS0H"


def continuous_card_x(model_id: object, global_frame: int, card_index: int) -> float | None:
    if normalize_model_id(model_id) != MODEL_WHAT_MALES_LEARN:
        return None
    value = continuous_body_x(global_frame, card_index)
    return None if value is None else float(value)


@lru_cache(maxsize=1)
def _fade_values() -> tuple[int, ...]:
    values = tuple(zlib.decompress(base64.b64decode(_FADE)))
    expected = MALES_FADE_END - MALES_FADE_START + 1
    if len(values) != expected:
        raise ValueError("invalid Canary fade table")
    return values


def males_fade_alpha(global_frame: int) -> float:
    frame = int(global_frame)
    if frame < MALES_FADE_START:
        return 1.0
    if frame > MALES_FADE_END:
        return 0.0
    return _fade_values()[frame - MALES_FADE_START] / 255.0
