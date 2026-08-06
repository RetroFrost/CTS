from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from .models import Project


SegmentKind = Literal[
    "brand_intro",
    "card_cycle",
    "end_wipe",
    "end_rise",
    "end_hold",
    "fade",
]


@dataclass(frozen=True, slots=True)
class Segment:
    kind: SegmentKind
    duration: float
    card_index: int = -1
    frame_count: int = 0


# Cadence measured from the supplied 60 FPS references. These remain integer
# frame counts so preview and export cannot disagree through float rounding.
OPENING_INTERVAL_FRAMES = 120
FOURTH_INTERVAL_FRAMES = 180
MAIN_INTERVAL_FRAMES = 204
END_WIPE_FRAMES = 25
END_RISE_FRAMES = 23
END_HOLD_FRAMES = 273
FADE_FRAMES = 48


def _seconds(frames: int, fps: int) -> float:
    return frames / max(1, fps)


def intro_frame_count(project: Project) -> int:
    return project.model.reference.first_content_frame if project.model.has_brand_intro else 0


def intro_duration(project: Project) -> float:
    return _seconds(intro_frame_count(project), project.settings.fps)


def _reference_cycle_frame_counts(card_count: int) -> list[int]:
    if card_count <= 0:
        return []

    frames: list[int] = []
    for index in range(card_count):
        if index == card_count - 1:
            frames.append(FOURTH_INTERVAL_FRAMES if index <= 3 else MAIN_INTERVAL_FRAMES)
        elif index < 3:
            frames.append(OPENING_INTERVAL_FRAMES)
        elif index == 3:
            frames.append(FOURTH_INTERVAL_FRAMES)
        else:
            frames.append(MAIN_INTERVAL_FRAMES)
    return frames


def cycle_frame_counts(project: Project) -> list[int]:
    # Locked models never stretch or truncate animation to meet a requested
    # duration. The card count changes how many canonical cycles are emitted;
    # every cycle itself remains byte-for-byte deterministic in timing.
    return _reference_cycle_frame_counts(len(project.cards))


def cycle_durations(project: Project) -> list[float]:
    fps = project.settings.fps
    return [_seconds(frames, fps) for frames in cycle_frame_counts(project)]


def outro_frame_count() -> int:
    return END_WIPE_FRAMES + END_RISE_FRAMES + END_HOLD_FRAMES + FADE_FRAMES


def outro_duration(project: Project) -> float:
    return _seconds(outro_frame_count(), project.settings.fps)


def reference_frame_count(project: Project) -> int:
    return intro_frame_count(project) + sum(cycle_frame_counts(project)) + outro_frame_count()


def reference_duration(project: Project) -> float:
    return _seconds(reference_frame_count(project), project.settings.fps)


def minimum_duration(project: Project) -> float:
    return reference_duration(project)


def card_start_frames(project: Project) -> list[int]:
    starts: list[int] = []
    cursor = intro_frame_count(project)
    for frames in cycle_frame_counts(project):
        starts.append(cursor)
        cursor += frames
    return starts


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
    intro_frames = intro_frame_count(project)
    if intro_frames:
        timeline.append(
            Segment("brand_intro", _seconds(intro_frames, fps), frame_count=intro_frames)
        )

    timeline.extend(
        Segment("card_cycle", _seconds(frames, fps), index, frames)
        for index, frames in enumerate(cycle_frame_counts(project))
    )
    timeline.extend(
        [
            Segment("end_wipe", _seconds(END_WIPE_FRAMES, fps), frame_count=END_WIPE_FRAMES),
            Segment("end_rise", _seconds(END_RISE_FRAMES, fps), frame_count=END_RISE_FRAMES),
            Segment("end_hold", _seconds(END_HOLD_FRAMES, fps), frame_count=END_HOLD_FRAMES),
            Segment("fade", _seconds(FADE_FRAMES, fps), frame_count=FADE_FRAMES),
        ]
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
