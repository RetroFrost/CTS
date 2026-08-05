from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from .models import Project


SegmentKind = Literal["card_cycle", "end_wipe", "end_rise", "end_hold", "fade"]


@dataclass(frozen=True, slots=True)
class Segment:
    kind: SegmentKind
    duration: float
    card_index: int = -1


# Cadence measured from the supplied 60 FPS reference:
# Cards 1-4 start at 0, 2, 4 and 6 seconds. Card four gets a three-second
# handoff; from card 5 onward the strip advances continuously, with each new
# vertical badge fall happening while the conveyor is still moving.
OPENING_INTERVAL = 2.00
FOURTH_INTERVAL = 3.00
MAIN_INTERVAL = 3.40
END_WIPE_SECONDS = 0.42
END_RISE_SECONDS = 0.38
END_HOLD_SECONDS = 4.55
FADE_SECONDS = 0.80


def _reference_cycle_durations(card_count: int) -> list[float]:
    if card_count <= 0:
        return []

    durations: list[float] = []
    for index in range(card_count):
        # Cards one through three arrive on the two-second opening beat. Card
        # four gets a three-second handoff so its badge can finish before the
        # conveyor begins. Every later card advances one continuous card-width
        # interval while its falling badge animation overlaps that movement.
        if index == card_count - 1:
            durations.append(FOURTH_INTERVAL if index <= 3 else MAIN_INTERVAL)
        elif index < 3:
            durations.append(OPENING_INTERVAL)
        elif index == 3:
            durations.append(FOURTH_INTERVAL)
        else:
            durations.append(MAIN_INTERVAL)
    return durations


def outro_duration() -> float:
    return END_WIPE_SECONDS + END_RISE_SECONDS + END_HOLD_SECONDS + FADE_SECONDS


def reference_duration(card_count: int) -> float:
    if card_count <= 0:
        return 0.0
    return sum(_reference_cycle_durations(card_count)) + outro_duration()


def minimum_duration(card_count: int) -> float:
    """Shortest valid duration while preserving the source-video cadence."""
    return reference_duration(card_count)


def cycle_durations(project: Project) -> list[float]:
    count = len(project.cards)
    if count <= 0:
        return []

    base = _reference_cycle_durations(count)
    if project.settings.auto_length:
        return base

    minimum = minimum_duration(count)
    # Native controls and the Python engine share the same rule: fixed length can
    # extend the source cadence, but never truncate an unfinished animation.
    custom = max(float(project.settings.custom_length_seconds), minimum)
    project.settings.custom_length_seconds = custom

    extra = custom - minimum
    extra_per_card = extra / count
    return [duration + extra_per_card for duration in base]


def card_start_times(project: Project) -> list[float]:
    starts: list[float] = []
    cursor = 0.0
    for duration in cycle_durations(project):
        starts.append(cursor)
        cursor += duration
    return starts


def content_duration(project: Project) -> float:
    return sum(cycle_durations(project))


def build_timeline(project: Project) -> list[Segment]:
    if not project.cards:
        return []

    timeline = [
        Segment("card_cycle", duration, index)
        for index, duration in enumerate(cycle_durations(project))
    ]
    timeline.extend(
        [
            Segment("end_wipe", END_WIPE_SECONDS),
            Segment("end_rise", END_RISE_SECONDS),
            Segment("end_hold", END_HOLD_SECONDS),
            Segment("fade", FADE_SECONDS),
        ]
    )
    return timeline


def total_duration(project: Project) -> float:
    return sum(segment.duration for segment in build_timeline(project))


def locate_segment(project: Project, seconds: float) -> tuple[Segment | None, float, float]:
    timeline = build_timeline(project)
    if not timeline:
        return None, 0.0, 0.0

    elapsed = max(0.0, seconds)
    cursor = 0.0
    for segment in timeline:
        end = cursor + segment.duration
        if elapsed < end or segment is timeline[-1]:
            local = min(max(elapsed - cursor, 0.0), segment.duration)
            progress = 1.0 if segment.duration <= 0 else local / segment.duration
            return segment, progress, cursor
        cursor = end
    return timeline[-1], 1.0, cursor
