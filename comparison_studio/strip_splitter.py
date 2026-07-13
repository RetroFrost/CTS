from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from PIL import Image, ImageDraw, ImageFont

from .data import FriendlyError


Orientation = Literal["horizontal", "vertical"]


@dataclass(slots=True, frozen=True)
class Divider:
    start: int
    end: int

    @property
    def thickness(self) -> int:
        return self.end - self.start


@dataclass(slots=True)
class SplitAnalysis:
    source_path: str
    orientation: Orientation
    source_size: tuple[int, int]
    dividers: list[Divider]
    sections: list[tuple[int, int]]
    expected_count: int | None
    confidence: float

    @property
    def count(self) -> int:
        return len(self.sections)

    @property
    def matches_expected(self) -> bool:
        return self.expected_count is None or self.count == self.expected_count

    def mismatch_message(self) -> str:
        if self.expected_count is None or self.matches_expected:
            return ""
        direction = "columns" if self.orientation == "horizontal" else "rows"
        return (
            f"Found {self.count} image {direction}, but the spreadsheet contains "
            f"{self.expected_count} cards. A divider may be missing or an extra "
            "uniform line may have been detected."
        )


def _sample_positions(length: int, count: int = 72) -> list[int]:
    if length <= count:
        return list(range(length))
    return [round(index * (length - 1) / (count - 1)) for index in range(count)]


def _is_uniform_line(
    image: Image.Image,
    coordinate: int,
    orientation: Orientation,
    tolerance: int,
) -> tuple[bool, tuple[int, int, int]]:
    width, height = image.size
    if orientation == "horizontal":
        samples = [image.getpixel((coordinate, y))[:3] for y in _sample_positions(height)]
    else:
        samples = [image.getpixel((x, coordinate))[:3] for x in _sample_positions(width)]

    channels = list(zip(*samples))
    ranges = [max(channel) - min(channel) for channel in channels]
    means = tuple(round(sum(channel) / len(channel)) for channel in channels)
    return max(ranges) <= tolerance, means


def _candidate_dividers(
    image: Image.Image,
    orientation: Orientation,
    min_thickness: int,
    tolerance: int,
) -> list[Divider]:
    axis_length = image.width if orientation == "horizontal" else image.height
    cross_length = image.height if orientation == "horizontal" else image.width
    maximum_thickness = max(12, round(axis_length * 0.012))

    uniform: list[tuple[bool, tuple[int, int, int]]] = [
        _is_uniform_line(image, coordinate, orientation, tolerance)
        for coordinate in range(axis_length)
    ]

    groups: list[Divider] = []
    start: int | None = None
    base_color: tuple[int, int, int] | None = None
    for coordinate, (is_uniform, color) in enumerate(uniform + [(False, (0, 0, 0))]):
        color_matches = (
            base_color is None
            or max(abs(color[channel] - base_color[channel]) for channel in range(3))
            <= tolerance * 2
        )
        if is_uniform and (start is None or color_matches):
            if start is None:
                start = coordinate
                base_color = color
            continue
        if start is not None:
            divider = Divider(start, coordinate)
            if min_thickness <= divider.thickness <= maximum_thickness:
                # Do not treat the outside edge of the strip as a separator.
                if divider.start > 0 and divider.end < axis_length:
                    groups.append(divider)
            start = coordinate if is_uniform else None
            base_color = color if is_uniform else None

    # Full-height solid artwork can mimic a divider. Keep only separators that
    # leave a useful image on each side.
    minimum_section = max(16, round(cross_length * 0.08))
    filtered: list[Divider] = []
    previous = 0
    for divider in groups:
        if divider.start - previous >= minimum_section:
            filtered.append(divider)
            previous = divider.end
    while filtered and axis_length - filtered[-1].end < minimum_section:
        filtered.pop()
    return filtered


def _sections(axis_length: int, dividers: list[Divider]) -> list[tuple[int, int]]:
    boundaries = [0]
    for divider in dividers:
        boundaries.extend((divider.start, divider.end))
    boundaries.append(axis_length)
    sections: list[tuple[int, int]] = []
    cursor = 0
    for divider in dividers:
        if divider.start > cursor:
            sections.append((cursor, divider.start))
        cursor = divider.end
    if cursor < axis_length:
        sections.append((cursor, axis_length))
    return sections


def _filter_for_expected_count(
    image: Image.Image,
    orientation: Orientation,
    dividers: list[Divider],
    expected_count: int | None,
    tolerance: int,
) -> list[Divider]:
    """Prefer a same-color divider family near the expected card boundaries.

    Artwork can contain short full-height uniform bands that technically look like
    dividers. Real sprite-strip separators normally share one color and sit near the
    boundaries implied by the number of spreadsheet rows.
    """
    needed = (expected_count - 1) if expected_count is not None else None
    if needed is None or needed < 1:
        return dividers

    axis_length = image.width if orientation == "horizontal" else image.height
    maximum_run = max(8, round(axis_length / expected_count * 0.04))
    atomic_runs: list[tuple[Divider, tuple[int, int, int]]] = []
    run_start: int | None = None
    run_color: tuple[int, int, int] | None = None
    color_tolerance = max(2, tolerance // 2)
    line_states = [
        _is_uniform_line(image, coordinate, orientation, tolerance)
        for coordinate in range(axis_length)
    ]
    for coordinate, (uniform, color) in enumerate(
        line_states + [(False, (0, 0, 0))]
    ):
        same_color = (
            run_color is None
            or max(
                abs(color[channel] - run_color[channel])
                for channel in range(3)
            )
            <= color_tolerance
        )
        if uniform and (run_start is None or same_color):
            if run_start is None:
                run_start = coordinate
                run_color = color
            continue
        if run_start is not None and run_color is not None:
            run = Divider(run_start, coordinate)
            if (
                2 <= run.thickness <= maximum_run
                and run.start > 0
                and run.end < axis_length
            ):
                atomic_runs.append((run, run_color))
        run_start = coordinate if uniform else None
        run_color = color if uniform else None

    if len(atomic_runs) < needed:
        return dividers

    representatives: list[tuple[Divider, tuple[int, int, int]]] = []
    representatives.extend(atomic_runs)

    cluster_tolerance = max(5, tolerance * 2)
    clusters: list[list[tuple[Divider, tuple[int, int, int]]]] = []
    for _seed_divider, seed_color in representatives:
        cluster = [
            entry
            for entry in representatives
            if max(
                abs(entry[1][channel] - seed_color[channel])
                for channel in range(3)
            )
            <= cluster_tolerance
        ]
        if len(cluster) >= needed and all(cluster != existing for existing in clusters):
            clusters.append(cluster)

    if not clusters:
        return dividers

    targets = [axis_length * index / expected_count for index in range(1, expected_count)]
    best_selection: list[Divider] | None = None
    best_score = float("inf")
    for cluster in clusters:
        available = list(cluster)
        selection: list[tuple[Divider, tuple[int, int, int]]] = []
        distance_score = 0.0
        for target in targets:
            if not available:
                break
            chosen = min(
                available,
                key=lambda entry: abs(
                    (entry[0].start + entry[0].end) / 2 - target
                ),
            )
            available.remove(chosen)
            selection.append(chosen)
            distance_score += abs(
                (chosen[0].start + chosen[0].end) / 2 - target
            ) / axis_length
        if len(selection) != needed:
            continue
        colors = [entry[1] for entry in selection]
        color_spread = sum(
            max(color[channel] for color in colors)
            - min(color[channel] for color in colors)
            for channel in range(3)
        ) / (255 * 3)
        thickness_spread = (
            max(entry[0].thickness for entry in selection)
            - min(entry[0].thickness for entry in selection)
        ) / max(1, axis_length)
        score = distance_score + color_spread * 0.35 + thickness_spread
        if score < best_score:
            best_score = score
            best_selection = sorted((entry[0] for entry in selection), key=lambda item: item.start)
    return best_selection or dividers


def _score(analysis: SplitAnalysis) -> float:
    if not analysis.sections:
        return -1000.0
    lengths = [end - start for start, end in analysis.sections]
    mean = sum(lengths) / len(lengths)
    variation = (
        math.sqrt(sum((length - mean) ** 2 for length in lengths) / len(lengths)) / mean
        if mean
        else 1.0
    )
    score = len(analysis.dividers) * 1.5 - variation * 4
    if analysis.expected_count is not None:
        score -= abs(analysis.count - analysis.expected_count) * 7
        if analysis.count == analysis.expected_count:
            score += 50
    return score


def analyze_strip(
    source_path: str | Path,
    expected_count: int | None = None,
    min_divider_thickness: int = 2,
    tolerance: int = 5,
) -> SplitAnalysis:
    path = Path(source_path).expanduser().resolve()
    if not path.is_file():
        raise FriendlyError(
            f"The image strip does not exist: {path}",
            "Choose an existing PNG, JPEG, WebP, or TIFF image.",
        )
    if expected_count is not None and expected_count < 1:
        raise FriendlyError(
            "Import spreadsheet data before importing an image strip.",
            "The spreadsheet rows tell the program how many image cuts to expect.",
        )
    try:
        with Image.open(path) as opened:
            image = opened.convert("RGB")
    except Exception as exc:
        raise FriendlyError(
            f"Could not read {path.name} as an image.",
            "Use PNG, JPEG, WebP, or TIFF.",
            str(exc),
        ) from exc

    if image.width < 32 or image.height < 32:
        raise FriendlyError(
            "The image strip is too small to split reliably.",
            "Use an image at least 32 × 32 pixels.",
        )

    analyses: list[SplitAnalysis] = []
    for orientation in ("horizontal", "vertical"):
        dividers = _candidate_dividers(
            image, orientation, min_divider_thickness, tolerance
        )
        dividers = _filter_for_expected_count(
            image, orientation, dividers, expected_count, tolerance
        )
        axis_length = image.width if orientation == "horizontal" else image.height
        sections = _sections(axis_length, dividers)
        analyses.append(
            SplitAnalysis(
                source_path=str(path),
                orientation=orientation,
                source_size=image.size,
                dividers=dividers,
                sections=sections,
                expected_count=expected_count,
                confidence=0.0,
            )
        )

    analyses.sort(key=_score, reverse=True)
    best = analyses[0]
    best.confidence = max(0.0, min(1.0, (_score(best) + 5) / 60))
    return best


def equal_slice_analysis(
    source_path: str | Path,
    expected_count: int,
    orientation: Orientation | None = None,
) -> SplitAnalysis:
    if expected_count < 1:
        raise FriendlyError("There are no spreadsheet cards to receive images.")
    path = Path(source_path).expanduser().resolve()
    try:
        with Image.open(path) as image:
            width, height = image.size
    except Exception as exc:
        raise FriendlyError(f"Could not read {path.name}.", details=str(exc)) from exc
    chosen: Orientation = orientation or ("horizontal" if width >= height else "vertical")
    axis_length = width if chosen == "horizontal" else height
    if axis_length < expected_count:
        raise FriendlyError(
            "The image is smaller than the number of requested cuts.",
            f"The {axis_length}-pixel axis cannot be divided into {expected_count} useful images.",
        )
    boundaries = [round(index * axis_length / expected_count) for index in range(expected_count + 1)]
    return SplitAnalysis(
        source_path=str(path),
        orientation=chosen,
        source_size=(width, height),
        dividers=[],
        sections=list(zip(boundaries[:-1], boundaries[1:])),
        expected_count=expected_count,
        confidence=1.0,
    )


def split_to_directory(
    analysis: SplitAnalysis,
    output_directory: str | Path,
) -> list[str]:
    if not analysis.matches_expected:
        raise FriendlyError(
            analysis.mismatch_message(),
            "Inspect the highlighted cuts, fix the strip, or choose Slice Equally.",
        )
    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    try:
        with Image.open(analysis.source_path) as opened:
            image = opened.convert("RGB")
            paths: list[str] = []
            for index, (start, end) in enumerate(analysis.sections, start=1):
                if analysis.orientation == "horizontal":
                    crop = image.crop((start, 0, end, image.height))
                else:
                    crop = image.crop((0, start, image.width, end))
                path = output / f"card_{index:03d}.png"
                crop.save(path, "PNG")
                paths.append(str(path))
            return paths
    except FriendlyError:
        raise
    except Exception as exc:
        raise FriendlyError(
            "The image strip was detected but could not be separated.",
            "Check that the destination is writable and the source image is not damaged.",
            str(exc),
        ) from exc


def preview_overlay(analysis: SplitAnalysis, max_size: tuple[int, int] = (1100, 620)) -> Image.Image:
    with Image.open(analysis.source_path) as opened:
        image = opened.convert("RGB")
    image.thumbnail(max_size, Image.Resampling.LANCZOS)
    source_width, source_height = analysis.source_size
    scale_x = image.width / source_width
    scale_y = image.height / source_height
    draw = ImageDraw.Draw(image, "RGBA")
    font = ImageFont.load_default()

    for index, (start, end) in enumerate(analysis.sections, start=1):
        if analysis.orientation == "horizontal":
            left, right = round(start * scale_x), round(end * scale_x)
            draw.rectangle((left, 0, right, image.height - 1), outline=(73, 230, 255, 220), width=2)
            label_position = (left + 6, 7)
        else:
            top, bottom = round(start * scale_y), round(end * scale_y)
            draw.rectangle((0, top, image.width - 1, bottom), outline=(73, 230, 255, 220), width=2)
            label_position = (7, top + 6)
        draw.rounded_rectangle(
            (
                label_position[0] - 3,
                label_position[1] - 2,
                label_position[0] + 26,
                label_position[1] + 15,
            ),
            radius=3,
            fill=(7, 12, 22, 215),
        )
        draw.text(label_position, str(index), font=font, fill=(255, 255, 255, 255))

    for divider in analysis.dividers:
        if analysis.orientation == "horizontal":
            x1, x2 = round(divider.start * scale_x), round(divider.end * scale_x)
            draw.rectangle((x1, 0, max(x1 + 1, x2), image.height), fill=(255, 65, 90, 150))
        else:
            y1, y2 = round(divider.start * scale_y), round(divider.end * scale_y)
            draw.rectangle((0, y1, image.width, max(y1 + 1, y2)), fill=(255, 65, 90, 150))
    return image
