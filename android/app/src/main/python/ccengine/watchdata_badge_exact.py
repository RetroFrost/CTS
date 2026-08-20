from __future__ import annotations

from . import renderer as _base
from .watchdata_strict import StrictReferenceFrameRenderer, _REFERENCE_BADGE_POLYGON


class BadgeExactReferenceFrameRenderer(StrictReferenceFrameRenderer):
    """Final badge-locked renderer.

    The measured opening affine correction is recomputed from the same strict
    polygon that is actually drawn.  This removes the last old-polygon
    dependency from the 1:1 badge path.
    """

    def _opening_entry_affine(
        self,
        local_frame: int,
        age: float,
    ) -> tuple[float, float, float, float, float, float]:
        keys = (
            (35.0, 1.075, 1.000, 0.0, -2.0),
            (40.0, 1.075, 1.000, 0.0, -2.0),
            (60.0, 1.095, 1.050, 1.0, 3.5),
            (80.0, 1.095, 1.050, -1.5, 4.5),
            (100.0, 1.085, 1.100, -2.7, -3.2),
            (110.0, 1.095, 1.100, -3.6, -5.5),
            (120.0, 1.115, 1.075, 1.0, -2.5),
            (130.0, 1.107, 1.090, 0.5, -3.5),
            (145.0, 1.060, 1.050, 0.0, -2.0),
            (155.0, 1.025, 1.020, 0.0, -1.0),
            (160.0, 1.000, 1.000, 0.0, 0.0),
        )

        base_age = min(float(age), _base.BADGE_ENTRY_END)
        affine = _base.badge_entry_affine(base_age)
        if local_frame > 120:
            affine = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        sx, sy, delta_cx, delta_cy = self._wd_sample(keys, float(local_frame))
        if local_frame >= 160:
            return affine

        m00, m01, m10, m11, tx, ty = affine
        transformed = [
            (m00 * x + m01 * y + tx, m10 * x + m11 * y + ty)
            for x, y in _REFERENCE_BADGE_POLYGON
        ]
        min_x = min(point[0] for point in transformed)
        max_x = max(point[0] for point in transformed)
        min_y = min(point[1] for point in transformed)
        max_y = max(point[1] for point in transformed)
        center_x = (min_x + max_x) / 2.0
        center_y = (min_y + max_y) / 2.0

        return (
            m00 * sx,
            m01 * sx,
            m10 * sy,
            m11 * sy,
            sx * tx + (1.0 - sx) * center_x + delta_cx,
            sy * ty + (1.0 - sy) * center_y + delta_cy,
        )
