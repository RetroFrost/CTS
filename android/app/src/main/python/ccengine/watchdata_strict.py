from __future__ import annotations

from . import renderer as _base
from .watchdata_release import ReleaseWatchDataFrameRenderer


def _linear_body_progress(local_time: float) -> float:
    """Interpolate the measured opening-body samples without adding easing.

    The source measurements in BODY_PROGRESS_KEYFRAMES already describe the
    motion curve. Applying smoothstep between them creates a second easing
    function which moves pixels away from their measured source-frame paths.
    Strict reference mode therefore interpolates directly between adjacent
    measured samples.
    """
    value = max(0.0, float(local_time))
    points = _base.BODY_PROGRESS_KEYFRAMES
    if value <= points[0][0]:
        return points[0][1]
    if value >= points[-1][0]:
        return points[-1][1]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if value <= x1:
            amount = (value - x0) / max(1e-9, x1 - x0)
            return _base.lerp(y0, y1, amount)
    return points[-1][1]


class StrictReferenceFrameRenderer(ReleaseWatchDataFrameRenderer):
    """Release renderer with source-frame motion treated as a hard contract.

    No generic easing is allowed where measured frame/pixel samples exist.
    Continuous conveyor positions, badge transforms and fades are still read
    from the exact reference tables inherited from the release renderer.
    """

    def _positions_for_frame(self, project, global_frame: int, starts: list[int]) -> dict[int, float]:
        pitch = self._active_profile.layout.slot_pitch
        timeline = self._active_profile.timeline
        frame = int(global_frame)

        # Continuous motion already uses decoded source-frame coordinates in
        # the parent renderer. Keep that exact path unchanged.
        if frame >= timeline.continuous_start_frame and len(project.cards) > 4:
            return super()._positions_for_frame(project, frame, starts)

        active = -1
        for index, start_frame in enumerate(starts[:4]):
            if frame >= start_frame:
                active = index
            else:
                break
        if active < 0:
            return {}

        positions = {index: index * pitch for index in range(active)}
        local_seconds = (frame - starts[active]) / 60.0
        movement = _linear_body_progress(local_seconds)
        if active == 0:
            positions[0] = _base.lerp(-pitch, 0.0, movement)
        else:
            positions[active] = _base.lerp((active - 1) * pitch, active * pitch, movement)
        return positions

    def _credits_x_for_frame(self, global_frame: int, starts: list[int]) -> float | None:
        pitch = self._active_profile.layout.slot_pitch
        frame = int(global_frame)
        active = -1
        for index, start_frame in enumerate(starts[:4]):
            if frame >= start_frame:
                active = index
            else:
                break

        if active < 0:
            return float(_base.REFERENCE_WIDTH)

        local_seconds = (frame - starts[active]) / 60.0
        movement = _linear_body_progress(local_seconds)
        if active == 0:
            return _base.lerp(_base.REFERENCE_WIDTH, _base.REFERENCE_WIDTH - pitch, movement)
        if active < 3:
            return float(_base.REFERENCE_WIDTH - pitch)
        if active == 3:
            return _base.lerp(_base.REFERENCE_WIDTH - pitch, _base.REFERENCE_WIDTH, movement)
        return None
