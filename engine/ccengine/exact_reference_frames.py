from __future__ import annotations

import base64
import struct
import zlib
from functools import lru_cache

from .model_registry import MODEL_WHAT_MALES_LEARN, normalize_model_id

MALES_CONVEYOR_START = 528
MALES_CONVEYOR_END = 16_335
MALES_CANONICAL_CONVEYOR_END = 11_841

MALES_CARD_PITCH_PX = 476.0
MALES_CARD_WIDTH_PX = 472.0
MALES_FADE_START = 12_180
MALES_FADE_END = 12_258

_MALES_ORIGINS = (
    "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"
)

_MALES_FADE_ALPHA = "eNoBTwCw//7+/Pv49fTz8e7s6+nm5ODc2tfT0MzJxsO/vbi1s6+sqaWioJyZl5KQjYqHhHt7enh1c25raWViYF1ZV1NPTkpHREE3NzUzLiwoIx8ZEgD7nS0H"


def _inflate(encoded: str) -> bytes:
    return zlib.decompress(base64.b64decode(encoded))


def _decode_origins(encoded: str, frame_count: int) -> tuple[int, ...]:
    packed = _inflate(encoded)
    if len(packed) != frame_count + 3:
        raise ValueError(
            f"Invalid exact-origin payload: expected {frame_count + 3} bytes, got {len(packed)}"
        )
    values = [struct.unpack_from("<i", packed, 0)[0]]
    for byte in packed[4:]:
        delta = byte if byte < 128 else byte - 256
        values.append(values[-1] + delta)
    if len(values) != frame_count:
        raise ValueError(
            f"Invalid exact-origin frame count: expected {frame_count}, got {len(values)}"
        )
    return tuple(values)


@lru_cache(maxsize=1)
def _origins(model_id: str) -> tuple[int, ...]:
    normalized = normalize_model_id(model_id)
    if normalized == MODEL_WHAT_MALES_LEARN:
        return _decode_origins(
            _MALES_ORIGINS,
            MALES_CONVEYOR_END - MALES_CONVEYOR_START + 1,
        )
    raise KeyError(normalized)


def continuous_card_x(
    model_id: object,
    global_frame: int,
    card_index: int,
) -> float | None:
    """Return the canonical card-left coordinate for one measured source frame."""
    normalized = normalize_model_id(model_id)
    frame = int(global_frame)
    index = int(card_index)

    if normalized == MODEL_WHAT_MALES_LEARN:
        if frame < MALES_CONVEYOR_START:
            return None
        held_frame = min(frame, MALES_CANONICAL_CONVEYOR_END)
        origin = _origins(normalized)[held_frame - MALES_CONVEYOR_START] / 2.0
        return origin + (index + 1) * MALES_CARD_PITCH_PX - MALES_CARD_WIDTH_PX

    return None


@lru_cache(maxsize=1)
def _males_fade_values() -> tuple[int, ...]:
    values = tuple(_inflate(_MALES_FADE_ALPHA))
    expected = MALES_FADE_END - MALES_FADE_START + 1
    if len(values) != expected:
        raise ValueError(f"Invalid fade payload: expected {expected} bytes, got {len(values)}")
    return values


def males_fade_alpha(global_frame: int) -> float:
    """Return the measured remaining-image opacity for the canonical fade."""
    frame = int(global_frame)
    if frame < MALES_FADE_START:
        return 1.0
    if frame > MALES_FADE_END:
        return 0.0
    return _males_fade_values()[frame - MALES_FADE_START] / 255.0
