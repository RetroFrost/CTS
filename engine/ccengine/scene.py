from __future__ import annotations

from dataclasses import dataclass
import math

from .models import Project
from .timing import Segment, card_start_frames, content_frame_count, locate_frame, seconds_to_frame


@dataclass(frozen=True, slots=True)
class FrameScene:
    """One immutable source-frame sample consumed by every renderer pass."""

    output_seconds: float
    global_frame: int
    segment: Segment | None
    segment_progress: float
    segment_start_frame: int
    content_end_frame: int
    card_starts: tuple[int, ...]


def build_frame_scene(project: Project, seconds: float) -> FrameScene:
    """Sample the timeline exactly once for preview and encoded output."""
    try:
        safe_seconds = float(seconds)
    except (TypeError, ValueError):
        safe_seconds = 0.0
    if not math.isfinite(safe_seconds):
        safe_seconds = 0.0
    safe_seconds = max(0.0, safe_seconds)

    global_frame = seconds_to_frame(project, safe_seconds)
    segment, local_frame, segment_start = locate_frame(project, global_frame)
    progress = (
        local_frame / max(1, segment.frame_count - 1)
        if segment is not None
        else 0.0
    )
    return FrameScene(
        output_seconds=safe_seconds,
        global_frame=global_frame,
        segment=segment,
        segment_progress=max(0.0, min(1.0, progress)),
        segment_start_frame=segment_start,
        content_end_frame=content_frame_count(project),
        card_starts=tuple(card_start_frames(project)),
    )
