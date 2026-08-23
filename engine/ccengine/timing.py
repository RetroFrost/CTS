from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from .models import Project
from .reference_profiles import get_reference_profile


SegmentKind = Literal[
    "card_cycle",
    "end_wipe",
    "end_rise",
    "end_hold",
    "fade",
    "black_tail",
]


@dataclass(frozen=True, slots=True)
class Segment:
    kind: SegmentKind
    duration: float
    card_index: int = -1
    frame_count: int = 0


def _seconds(frames: int, fps: int) -> float:
    return frames / max(1, fps)


def intro_frame_count(project: Project) -> int:
    return 0


def intro_duration(project: Project) -> float:
    return _seconds(intro_frame_count(project), project.settings.fps)


def _reference_cycle_frame_counts(project: Project) -> list[int]:
    card_count = len(project.cards)
    if card_count <= 0:
        return []
    timeline = get_reference_profile(project.settings.model_id).timeline
    starts = [timeline.card_start_frame(index) for index in range(card_count)]
    end = timeline.content_end_frame(card_count)
    return [
        (starts[index + 1] if index + 1 < card_count else end) - start
        for index, start in enumerate(starts)
    ]


def _custom_content_end_frame(project: Project) -> int:
    """Return the requested content boundary without retiming fixed animations.

    The outro keeps its measured frame count. The opening four-card sequence also
    keeps its original addresses. Only the later continuous conveyor is allowed
    to occupy more or fewer frames.
    """
    profile = get_reference_profile(project.settings.model_id).timeline
    fps = max(1, project.settings.fps)
    requested_total = max(1, round(float(project.settings.custom_length_seconds) * fps))
    requested_content = requested_total - profile.outro_frames
    if len(project.cards) <= 4:
        fixed_opening = profile.opening_card_ends[max(0, len(project.cards) - 1)]
        return max(fixed_opening, requested_content)
    # One frame per conveyor interval is the hard mathematical minimum. Badge
    # fall/shine clocks are not scaled and may overlap at extremely short user
    # lengths, which is preferable to inventing faster badge animation.
    intervals = len(project.cards) - 4 + profile.continuous_tail_steps
    return max(profile.continuous_start_frame + intervals, requested_content)


def cycle_frame_counts(project: Project) -> list[int]:
    if project.settings.auto_length:
        return _reference_cycle_frame_counts(project)
    starts = card_start_frames(project)
    if not starts:
        return []
    end = _custom_content_end_frame(project)
    return [
        (starts[index + 1] if index + 1 < len(starts) else end) - start
        for index, start in enumerate(starts)
    ]


def cycle_durations(project: Project) -> list[float]:
    fps = project.settings.fps
    return [_seconds(frames, fps) for frames in cycle_frame_counts(project)]


def outro_frame_count(project: Project) -> int:
    return get_reference_profile(project.settings.model_id).timeline.outro_frames


def outro_duration(project: Project) -> float:
    return _seconds(outro_frame_count(project), project.settings.fps)


def reference_frame_count(project: Project) -> int:
    return intro_frame_count(project) + sum(cycle_frame_counts(project)) + outro_frame_count(project)


def reference_duration(project: Project) -> float:
    return _seconds(reference_frame_count(project), project.settings.fps)


def minimum_duration(project: Project) -> float:
    profile = get_reference_profile(project.settings.model_id).timeline
    if len(project.cards) <= 4:
        content = profile.opening_card_ends[max(0, len(project.cards) - 1)]
    else:
        content = profile.continuous_start_frame + len(project.cards) - 4
    return _seconds(content + profile.outro_frames, project.settings.fps)


def card_start_frames(project: Project) -> list[int]:
    timeline = get_reference_profile(project.settings.model_id).timeline
    count = len(project.cards)
    if project.settings.auto_length or count <= 4:
        return [timeline.card_start_frame(index) for index in range(count)]

    starts = list(timeline.opening_card_starts)
    intervals = count - 4 + timeline.continuous_tail_steps
    span = _custom_content_end_frame(project) - timeline.continuous_start_frame
    for index in range(4, count):
        offset = index - 4
        starts.append(timeline.continuous_start_frame + round(span * offset / intervals))
    return starts[:count]


def card_start_times(project: Project) -> list[float]:
    fps = project.settings.fps
    return [_seconds(frame, fps) for frame in card_start_frames(project)]


def card_content_frame_count(project: Project) -> int:
    return sum(cycle_frame_counts(project))


def card_content_duration(project: Project) -> float:
    return _seconds(card_content_frame_count(project), project.settings.fps)


def content_frame_count(project: Project) -> int:
    return intro_frame_count(project) + card_content_frame_count(project)


def content_duration(project: Project) -> float:
    return _seconds(content_frame_count(project), project.settings.fps)


def build_timeline(project: Project) -> list[Segment]:
    if not project.cards:
        return []

    fps = project.settings.fps
    timeline: list[Segment] = []
    timeline.extend(
        Segment("card_cycle", _seconds(frames, fps), index, frames)
        for index, frames in enumerate(cycle_frame_counts(project))
    )
    profile = get_reference_profile(project.settings.model_id).timeline
    outro_segments = (
        ("end_wipe", profile.end_wipe_frames),
        ("end_rise", profile.end_rise_frames),
        ("end_hold", profile.end_hold_frames),
        ("fade", profile.fade_frames),
        ("black_tail", profile.black_tail_frames),
    )
    timeline.extend(
        Segment(kind, _seconds(frames, fps), frame_count=frames)
        for kind, frames in outro_segments
        if frames > 0
    )
    return timeline


def total_frame_count(project: Project) -> int:
    return sum(segment.frame_count for segment in build_timeline(project))


def total_duration(project: Project) -> float:
    return _seconds(total_frame_count(project), project.settings.fps)


def frame_to_seconds(project: Project, frame_index: int) -> float:
    return max(0, int(frame_index)) / project.settings.fps


def seconds_to_frame(project: Project, seconds: float) -> int:
    # Frame selection always floors to the frame whose presentation interval
    # contains the requested timestamp. This keeps preview/export deterministic.
    return max(0, int(max(0.0, float(seconds)) * project.settings.fps + 1e-9))


def locate_frame(project: Project, frame_index: int) -> tuple[Segment | None, int, int]:
    timeline = build_timeline(project)
    if not timeline:
        return None, 0, 0

    frame = max(0, int(frame_index))
    cursor = 0
    for segment in timeline:
        end = cursor + segment.frame_count
        if frame < end or segment is timeline[-1]:
            local = min(max(frame - cursor, 0), max(0, segment.frame_count - 1))
            return segment, local, cursor
        cursor = end
    return timeline[-1], max(0, timeline[-1].frame_count - 1), cursor


def locate_segment(project: Project, seconds: float) -> tuple[Segment | None, float, float]:
    frame = seconds_to_frame(project, seconds)
    segment, local_frame, start_frame = locate_frame(project, frame)
    if segment is None:
        return None, 0.0, 0.0
    denominator = max(1, segment.frame_count - 1)
    progress = local_frame / denominator
    return segment, progress, _seconds(start_frame, project.settings.fps)
