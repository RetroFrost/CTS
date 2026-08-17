from __future__ import annotations

"""Dense visible red-hex bounds measured directly from the Canary reference.

The table covers one complete continuous-card badge life after its top-edge
entry is fully visible (local source frames 200..850). Values are relative to
the card body's left edge: x offset, y, width, height. Because the continuous
strip repeats on the same 214-frame source clock, the same table applies to
every post-opening card.
"""

import base64
import struct
import zlib
from functools import lru_cache

FIRST_LOCAL_FRAME = 200
LAST_LOCAL_FRAME = 850

_PAYLOAD = (
    "eNqtlO1PE0EQh2f85AclpAWCilCRIGlrIYSQtiFCsCVtNYpKr5UQpC1Go7yl1z8eiTFOr9vbvX29a/j0ZGbuNzszO3t1eAQVvMA68QCvsAZTxBtsECvEesA+cZr8I1YZKzhg/gHpUkG8QRx9PxPoazBL/uvArlL+RuC/CvPVGBtBvnFe03mqf6jbD+N+SNEvx6vSebwO33CeXsfz+czvS7pBGK9r+qtSXN+3XifWKetkv76/vrYOeZ4uvxhvJMznum9d/ZUYdcTZk6T16+I1yz4M/ab7sZ9n3y9TPnHv9HX0jXup06lz9g3zlPdZfgem/nzlnFqkHnnOA21ecc71BOcdGN+rL81Ffaf6OgZKnaZ3LN+rXuc75jz6v+7Tf/U9cY/+o+8gDbt4iR9hDsr4kzgPJTzHT/AUtrBLXCCe4WHAU/xM3CR+gUXYwBOyRzwiruNxwA1iE5aggF/JzhDbZGfgNWOecej3BFukJ3HsbxGzE+i8e9YN7ZwQb8bU6ev3WD6PxT0W9yLxrPCdJ+k8i45/L9ev5rPrTHFPmgeP677POfrLG3WmOtqSrhXxc11LOk9/D1njPph1cj1NbX9tpb/o/un9uQn3Mkn9yebM463Ifas6+z63I/uTfJ/Ne+JZ63D3J7/vaH+uOdvvLee4v6zmvbZi1JGNsSdeLJ1n6M+L1DHJ/03+r3D7BemaxGWyjyjPMqwR27ACr/AQj2EVXuIHPIE1yGCDmIUlPMBTyMMzrIT8BgWYx70InwRcJ3sXO7ABc7iDZ4xDe5ZxRmA3tDeJ5QjTxK7FnxLiXau/pMQ7kXgxoktJ+cy6slWXcp5XtNav1sF1+jqKzvNkf0/QpY35itrvVV1JipetOq4vKffQkfJ2E+RNG+accuYrxZxzSZlzTzt/Xl9PmuM43jXU7+prsr6L2jmb9jKt6Hj/Yv2u9xPnXbnegenddaS8PYOup6nDvUf6/maUedzff6MXs+9k/5t48xffnasOWzwVs860pc5z4nRwL1vEbbK3YIr4HbbhMW7iDyjCQywQy8Q8/oIdeIBrxDeAuIq/iYAreEH8B0PuEZfxkviXcWiP/Bmy98m/GLJPvCPeMF5Z+IfxNsIK+RfwmnhLHH93yXTc/5b4PNRxv8poPs47IR5Xd838/wGlF5Z0"
)


@lru_cache(maxsize=1)
def _values() -> tuple[int, ...]:
    raw = zlib.decompress(base64.b64decode(_PAYLOAD))
    values = struct.unpack("<" + "h" * (len(raw) // 2), raw)
    expected = (LAST_LOCAL_FRAME - FIRST_LOCAL_FRAME + 1) * 4
    if len(values) != expected:
        raise ValueError(f"Invalid Canary badge-bound payload: {len(values)} != {expected}")
    return values


def continuous_badge_red_bounds(local_frame: int) -> tuple[int, int, int, int] | None:
    """Return (card-relative x, y, width, height) for a measured source frame."""
    frame = int(local_frame)
    if frame < FIRST_LOCAL_FRAME:
        return None
    frame = min(frame, LAST_LOCAL_FRAME)
    offset = (frame - FIRST_LOCAL_FRAME) * 4
    values = _values()
    return tuple(values[offset : offset + 4])  # type: ignore[return-value]
