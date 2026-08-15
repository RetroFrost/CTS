from __future__ import annotations

import base64
import struct
import zlib
from functools import lru_cache

from .model_registry import (
    MODEL_TYPES_OF_RELATIONSHIPS,
    MODEL_WHAT_MALES_LEARN,
    normalize_model_id,
)

MALES_CONVEYOR_START = 528
MALES_CONVEYOR_END = 16_335
RELATIONSHIPS_CONVEYOR_START = 896
RELATIONSHIPS_CONVEYOR_END = 10_701
RELATIONSHIPS_FINAL_START = 10_670
RELATIONSHIPS_FINAL_END = 11_129

MALES_CARD_PITCH_PX = 476.0
MALES_CARD_WIDTH_PX = 480.0
RELATIONSHIPS_CARD_PITCH_PX = 483.0
RELATIONSHIPS_CARD_WIDTH_PX = 475.0
_SENTINEL = -32_768

_MALES_ORIGINS = (
    "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"
)

_RELATIONSHIPS_ORIGINS = (
    "eNrFWtFy4zAIzP//aZ/u/SQ57cVugwQsC/L0ZjoZR1hCrGBBpH+ez+fj8eijf/19fchPe2D+mx/VvOnRl1Xn1WXI1PdoG7ebg2Xc"
    "tiD2Vavfa4529JfCf+PXY/9+PD/FI5KpAVJWXRO9Jta0H+XA+Eb8BCeccUEmlI7BaTL3fYpSOKnl1V5IfE3ZBYPY3Oup2bv4QQ/v"
    "cFUqZ8h5wv1HBABSvcrI1zScffh7FiFMbtFeIpbZawq01ACSxYd2wqAOhvB3CObwPZS0O47vWLa6FznvClTFupjJT5btmL7zuc3N"
    "zOu3RXUyo/uvdeVZpu8RsmUhNSMXRHpeGzACyPQMLZtgRpvp71yDcEDYOtHeR7DnxBEgYPDyPVJoGNEHi8MEWsR6BAEmjkDlAQUJ"
    "VhIHNRWR+JCE+93JNbRTmKp5qoLfknZkkbb4LXxZDOA6u4URNqWYY8dbUzypDq8WKNNBR7SF95wiqzTV5rjDkS2ggXkGy6+RUkhs"
    "pDMZMLck1ZIktJf1ZbG5ChWATt4kzieel7dHafJdwzvhjob9lRAOTD7KGx8dgbpC66QQlS7ThWr8aKsXLChRB/QjVXvzEtxBdqG0"
    "1R6APlUU+A6V37fFOVvd2xjx9X7Jnri+S94TQFXrt/gIy3SfJHH7gvlIJ/HEtSDOwq3YC8HA9GIz5J0Kw5edW0d1zzmqDb25HZ25"
    "2t6yUZJRtq7SWzfUct/vv1whyQSWKgDcuNj57QDlMcF3ZM2IG2hkAyFDtXZJgDswUSlR8WFNw224ycWvalGq2iwt0dnlS4m4K2Z3"
    "NeNSdk81cUUdlG4iuKtlk+nUYUnl4FfoWtrHL0MXZax8W7FSuqht+c13qntb9O+oZIubojdkYVsWp5y5tGy7P1dj3jf7D6iVTF/5"
    "Y/xmqsVxcTUkatmM7ksK02r/N4C6lWRLFObiuH5IeSuuvyi+UyS0RbX0vHZ0P+0WCpbfKpiLVIvLgBdJIF+kfyAdnSlmMmbHqitO"
    "QHUb6l0XVZ4cZDe3J82Jl7hUL8nNgeHvxyekVsR/"
)

_RELATIONSHIPS_LAST_X = (
    "eNrtjskrxAEcxd/3vZ+RZYriYikXDmpuFAdOlBRRalIuiIvUlFDKcqFEzUFxcBBCSjkIB8tVZA6iIdukSZYkuyTL749w1Of41ljH"
    "4zgOHTjf+tKnPvSuVz3rSQ+6151uda0rXSqqC0V0rlMd60hhHWhfewppU4uaUFA9alWdylWgHKVIemSEIa5xnmPsZxvrWcVi+pjB"
    "eH7YtR3Zlq3YrI3agHVYs/mtzAot19LNaz94RBRhbGMdi5jGGIbQh3a0oB5+VKIURciHDznIQhpSkYRExCEWMXCQiXJ0YQERJFuJ"
    "2zxnh+Zhnrs/zGWekcpWqRrUrRHNaElrWtWkBhWQX8Wu5tWb+3zH9U4xyF4G2MhaVrOCNWxiJwc5zgVucJcnvOELf+hRgpv655+/"
    "5BcT1WjC"
)

_RELATIONSHIPS_FADE = (
    "eNoBNgDJ//v59O/s6OPg3NbTz8vHxMC0sKyoo5+bmJOOi4eDf3p2cm9jX1tXU05LR0I/OjUyLSolIR4XEvoYHLk="
)


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


@lru_cache(maxsize=2)
def _origins(model_id: str) -> tuple[int, ...]:
    normalized = normalize_model_id(model_id)
    if normalized == MODEL_WHAT_MALES_LEARN:
        return _decode_origins(
            _MALES_ORIGINS,
            MALES_CONVEYOR_END - MALES_CONVEYOR_START + 1,
        )
    if normalized == MODEL_TYPES_OF_RELATIONSHIPS:
        return _decode_origins(
            _RELATIONSHIPS_ORIGINS,
            RELATIONSHIPS_CONVEYOR_END - RELATIONSHIPS_CONVEYOR_START + 1,
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
        held_frame = min(frame, MALES_CONVEYOR_END)
        origin = _origins(normalized)[held_frame - MALES_CONVEYOR_START] / 2.0
        return origin + (index + 1) * MALES_CARD_PITCH_PX - MALES_CARD_WIDTH_PX

    if normalized == MODEL_TYPES_OF_RELATIONSHIPS:
        if frame < RELATIONSHIPS_CONVEYOR_START or frame > RELATIONSHIPS_CONVEYOR_END:
            return None
        origin = _origins(normalized)[frame - RELATIONSHIPS_CONVEYOR_START] / 2.0
        return (
            origin
            + (index + 1) * RELATIONSHIPS_CARD_PITCH_PX
            - RELATIONSHIPS_CARD_WIDTH_PX
        )

    return None


@lru_cache(maxsize=1)
def _relationships_last_x_values() -> tuple[int, ...]:
    packed = _inflate(_RELATIONSHIPS_LAST_X)
    expected = (RELATIONSHIPS_FINAL_END - RELATIONSHIPS_FINAL_START + 1) * 2
    if len(packed) != expected:
        raise ValueError(
            f"Invalid Relationships last-card payload: expected {expected} bytes, got {len(packed)}"
        )
    return tuple(struct.unpack(f"<{expected // 2}h", packed))


def relationships_last_card_x(global_frame: int) -> float | None:
    frame = int(global_frame)
    if frame < RELATIONSHIPS_FINAL_START or frame > RELATIONSHIPS_FINAL_END:
        return None
    value = _relationships_last_x_values()[frame - RELATIONSHIPS_FINAL_START]
    return None if value == _SENTINEL else float(value)


@lru_cache(maxsize=1)
def _relationships_fade_values() -> bytes:
    values = _inflate(_RELATIONSHIPS_FADE)
    if len(values) != 54:
        raise ValueError(f"Invalid Relationships fade payload: expected 54 bytes, got {len(values)}")
    return values


def relationships_fade_alpha(global_frame: int) -> float:
    """Measured remaining-image alpha for source frames f11076..f11129."""
    frame = int(global_frame)
    if frame < 11_076:
        return 1.0
    if frame > 11_129:
        return 0.0
    return _relationships_fade_values()[frame - 11_076] / 255.0
