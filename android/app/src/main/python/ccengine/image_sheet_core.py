from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable
import copy
import json
import math
import re
import shutil

from PIL import Image, ImageChops, ImageEnhance, ImageFilter, ImageOps, ImageStat
from .models import Card


@dataclass(frozen=True, slots=True)
class CropRect:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return max(0, self.right - self.left)

    @property
    def height(self) -> int:
        return max(0, self.bottom - self.top)

    def as_pillow_box(self) -> tuple[int, int, int, int]:
        return self.left, self.top, self.right, self.bottom


@dataclass(frozen=True, slots=True)
class GridDetection:
    rows: int
    columns: int
    rectangles: tuple[CropRect, ...]
    confidence: float
    method: str = "automatic"

    @property
    def count(self) -> int:
        return len(self.rectangles)


class ImageSheetProcessor:
    """Detect and split contact sheets without OpenCV or other heavy dependencies."""

    TARGET_CELL_ASPECT = 480 / 830
    MAX_ROWS = 20
    MAX_COLUMNS = 20

    def __init__(self, image: Image.Image, cancel_check: Callable[[], bool] | None = None) -> None:
        self._cancel_check = cancel_check
        self._check_cancelled()
        self.image = image.convert("RGB")
        self.width, self.height = self.image.size
        if self.width < 16 or self.height < 16:
            raise ValueError("The image sheet is too small to split.")
        self._analysis = self._make_analysis_image(self.image)
        self._check_cancelled()
        self._x_line_scores = self._compute_line_scores("x")
        self._y_line_scores = self._compute_line_scores("y")

    def _check_cancelled(self) -> None:
        if self._cancel_check is not None and self._cancel_check():
            raise RuntimeError("Image-sheet import cancelled.")

    @staticmethod
    def _make_analysis_image(image: Image.Image) -> Image.Image:
        maximum = 640
        scale = min(1.0, maximum / max(image.size))
        if scale >= 1.0:
            return image.copy()
        size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
        return image.resize(size, Image.Resampling.BILINEAR)

    @classmethod
    def from_path(
        cls, path: str | Path, cancel_check: Callable[[], bool] | None = None
    ) -> "ImageSheetProcessor":
        if cancel_check is not None and cancel_check():
            raise RuntimeError("Image-sheet import cancelled.")
        with Image.open(path) as source:
            source.load()
            if cancel_check is not None and cancel_check():
                raise RuntimeError("Image-sheet import cancelled.")
            return cls(source, cancel_check=cancel_check)

    def _compute_line_scores(self, axis: str) -> list[float]:
        length = self._analysis.width if axis == "x" else self._analysis.height
        scores = [0.0] * length
        for position in range(1, length - 1):
            if position % 32 == 0:
                self._check_cancelled()
            scores[position] = self._line_score(self._analysis, axis, position)
        return scores

    @staticmethod
    def _line_score(image: Image.Image, axis: str, position: int) -> float:
        """Score likely gutters/dividers: uniform extreme lines plus adjacent contrast."""
        width, height = image.size
        if axis == "x":
            position = max(1, min(width - 2, position))
            line = image.crop((position, 0, position + 1, height)).resize((1, 96))
            before = image.crop((position - 1, 0, position, height)).resize((1, 96))
            after = image.crop((position + 1, 0, position + 2, height)).resize((1, 96))
        else:
            position = max(1, min(height - 2, position))
            line = image.crop((0, position, width, position + 1)).resize((96, 1))
            before = image.crop((0, position - 1, width, position)).resize((96, 1))
            after = image.crop((0, position + 1, width, position + 2)).resize((96, 1))

        stat = ImageStat.Stat(line)
        mean = sum(stat.mean) / 3.0
        variance = sum(stat.var) / 3.0
        uniformity = 1.0 - min(1.0, variance / 4200.0)
        extremeness = abs(mean - 127.5) / 127.5
        difference = ImageChops.difference(before, after)
        edge = min(1.0, (sum(ImageStat.Stat(difference).mean) / 3.0) / 80.0)
        return 0.54 * uniformity + 0.22 * extremeness + 0.24 * edge

    def _best_boundary(self, axis: str, ideal_original: float, span_original: float) -> tuple[int, float, int, int]:
        analysis = self._analysis
        original_length = self.width if axis == "x" else self.height
        analysis_length = analysis.width if axis == "x" else analysis.height
        scale = analysis_length / original_length
        ideal = ideal_original * scale
        search = max(2, round(span_original * scale * 0.24))
        lower = max(1, round(ideal - search))
        upper = min(analysis_length - 2, round(ideal + search))

        score_table = self._x_line_scores if axis == "x" else self._y_line_scores
        best_position = max(range(lower, upper + 1), key=lambda position: score_table[position])
        best_score = score_table[best_position]

        threshold = max(0.58, best_score * 0.88)
        run_left = best_position
        run_right = best_position
        while run_left - 1 >= lower and score_table[run_left - 1] >= threshold:
            run_left -= 1
        while run_right + 1 <= upper and score_table[run_right + 1] >= threshold:
            run_right += 1

        inv_scale = original_length / analysis_length
        center_original = round(best_position * inv_scale)
        left_original = round(run_left * inv_scale)
        right_original = round((run_right + 1) * inv_scale)
        return center_original, best_score, left_original, right_original

    @staticmethod
    def _candidate_grids() -> list[tuple[int, int]]:
        candidates: list[tuple[int, int]] = []
        for rows in range(1, ImageSheetProcessor.MAX_ROWS + 1):
            for columns in range(1, ImageSheetProcessor.MAX_COLUMNS + 1):
                cells = rows * columns
                if cells == 1 or cells > 400:
                    continue
                candidates.append((rows, columns))
        return candidates

    def _regular_separator_runs(self, axis: str, threshold: float = 0.70) -> list[tuple[int, int]]:
        """Find repeated full-sheet gutters and return their spans in original pixels."""
        scores = self._x_line_scores if axis == "x" else self._y_line_scores
        analysis_length = len(scores)
        runs: list[tuple[int, int]] = []
        start: int | None = None
        for position, score in enumerate(scores):
            if score >= threshold and start is None:
                start = position
            elif score < threshold and start is not None:
                runs.append((start, position - 1))
                start = None
        if start is not None:
            runs.append((start, analysis_length - 1))

        # Ignore the sheet's outside border and merge the two edges of a thin gutter.
        inner = [
            run for run in runs
            if (run[0] + run[1]) / 2.0 > analysis_length * 0.025
            and (run[0] + run[1]) / 2.0 < analysis_length * 0.975
        ]
        merge_gap = max(2, round(analysis_length * 0.008))
        merged: list[tuple[int, int]] = []
        for run in inner:
            if merged and run[0] - merged[-1][1] - 1 <= merge_gap:
                merged[-1] = (merged[-1][0], run[1])
            else:
                merged.append(run)

        if not merged:
            return []
        centers = [(left + right) / 2.0 for left, right in merged]
        if len(centers) >= 3:
            gaps = [right - left for left, right in zip(centers, centers[1:])]
            mean_gap = sum(gaps) / len(gaps)
            if mean_gap <= 0:
                return []
            variance = sum((gap - mean_gap) ** 2 for gap in gaps) / len(gaps)
            coefficient = math.sqrt(variance) / mean_gap
            if coefficient > 0.18:
                return []

        original_length = self.width if axis == "x" else self.height
        scale = original_length / analysis_length
        return [
            (max(0, round(left * scale)), min(original_length, round((right + 1) * scale)))
            for left, right in merged
        ]

    def _separator_grid_hint(self) -> GridDetection | None:
        vertical_runs = self._regular_separator_runs("x")
        horizontal_runs = self._regular_separator_runs("y")
        if not vertical_runs and not horizontal_runs:
            return None
        rows = len(horizontal_runs) + 1
        columns = len(vertical_runs) + 1
        if rows > self.MAX_ROWS or columns > self.MAX_COLUMNS or rows * columns > 400:
            return None
        rectangles = self._rectangles_from_runs(rows, columns, vertical_runs, horizontal_runs)
        if not rectangles or min(rect.width for rect in rectangles) < 8 or min(rect.height for rect in rectangles) < 8:
            return None
        return GridDetection(rows, columns, tuple(rectangles), 0.98, method="automatic")

    def _continuous_strip_hint(self, preferred_count: int | None) -> GridDetection | None:
        """Recognise a generated CTS strip whose width is exactly N card lengths.

        These sheets intentionally have no gutters because one scene continues across
        the card boundaries. Divider detection must not invent crops inside the scene.
        """
        if not preferred_count or preferred_count < 2 or preferred_count > self.MAX_COLUMNS:
            return None
        expected_aspect = preferred_count * self.TARGET_CELL_ASPECT
        actual_aspect = self.width / self.height
        distance = abs(math.log(max(1e-9, actual_aspect / expected_aspect)))
        if distance > 0.035:
            return None
        detection = self.manual_grid(1, preferred_count)
        return GridDetection(
            rows=1,
            columns=preferred_count,
            rectangles=detection.rectangles,
            confidence=1.0,
            method="continuous-cts-strip",
        )

    def detect(self, preferred_count: int | None = None) -> GridDetection:
        # A CTS-length continuous strip deliberately has no separators. Its exact overall
        # aspect is stronger evidence than line detection and preserves one shared scene.
        strip_hint = self._continuous_strip_hint(preferred_count)
        if strip_hint is not None:
            return strip_hint

        # Regular full-height/full-width gutters are stronger evidence than the requested
        # image count. This correctly recognises dense 10x10 sheets as well as 5x2 sheets.
        separator_hint = self._separator_grid_hint()
        if separator_hint is not None:
            return separator_hint

        best: tuple[float, GridDetection] | None = None
        sheet_aspect = self.width / self.height

        for candidate_index, (rows, columns) in enumerate(self._candidate_grids()):
            if candidate_index % 8 == 0:
                self._check_cancelled()
            count = rows * columns
            cell_aspect = sheet_aspect * rows / columns
            aspect_distance = abs(math.log(max(0.05, cell_aspect) / self.TARGET_CELL_ASPECT))
            aspect_score = math.exp(-1.65 * aspect_distance)

            vertical_scores: list[float] = []
            horizontal_scores: list[float] = []
            vertical_runs: list[tuple[int, int]] = []
            horizontal_runs: list[tuple[int, int]] = []
            cell_width = self.width / columns
            cell_height = self.height / rows

            for index in range(1, columns):
                _, score, left, right = self._best_boundary("x", index * cell_width, cell_width)
                vertical_scores.append(score)
                vertical_runs.append((left, right))
            for index in range(1, rows):
                _, score, top, bottom = self._best_boundary("y", index * cell_height, cell_height)
                horizontal_scores.append(score)
                horizontal_runs.append((top, bottom))

            separator_scores = vertical_scores + horizontal_scores
            separator_score = sum(separator_scores) / len(separator_scores) if separator_scores else 0.0
            weakest_separator = min(separator_scores) if separator_scores else 0.0

            count_score = 0.0
            if preferred_count and preferred_count > 1:
                delta = abs(count - preferred_count)
                count_score = math.exp(-delta / max(1.0, preferred_count * 0.24))

            # A user-provided expected count is a real hint, but separator evidence still
            # protects against inventing a dense grid in a normal picture.
            weak_penalty = max(0.0, 0.54 - weakest_separator) * 0.80 if separator_scores else 0.25
            if preferred_count and preferred_count > 1:
                complexity_penalty = max(0, count - 160) * 0.001 * (1.0 - count_score)
                total_score = (
                    0.44 * separator_score
                    + 0.18 * aspect_score
                    + 0.38 * count_score
                    - complexity_penalty
                    - weak_penalty
                )
            else:
                complexity_penalty = max(0, count - 20) * 0.006
                total_score = (
                    0.48 * separator_score
                    + 0.32 * aspect_score
                    - complexity_penalty
                    - weak_penalty
                )

            rectangles = self._rectangles_from_runs(rows, columns, vertical_runs, horizontal_runs)
            if not rectangles or min(rect.width for rect in rectangles) < 8 or min(rect.height for rect in rectangles) < 8:
                continue
            detection = GridDetection(
                rows=rows,
                columns=columns,
                rectangles=tuple(rectangles),
                confidence=max(0.0, min(1.0, total_score)),
            )
            if best is None or total_score > best[0]:
                best = total_score, detection

        if best is None:
            return self.manual_grid(2, 5)
        return best[1]

    def _rectangles_from_runs(
        self,
        rows: int,
        columns: int,
        vertical_runs: list[tuple[int, int]],
        horizontal_runs: list[tuple[int, int]],
    ) -> list[CropRect]:
        x_starts = [0] + [max(0, min(self.width, right)) for _, right in vertical_runs]
        x_ends = [max(0, min(self.width, left)) for left, _ in vertical_runs] + [self.width]
        y_starts = [0] + [max(0, min(self.height, bottom)) for _, bottom in horizontal_runs]
        y_ends = [max(0, min(self.height, top)) for top, _ in horizontal_runs] + [self.height]

        rectangles: list[CropRect] = []
        for row in range(rows):
            for column in range(columns):
                left = x_starts[column]
                right = x_ends[column]
                top = y_starts[row]
                bottom = y_ends[row]
                if right <= left or bottom <= top:
                    return []
                rectangles.append(CropRect(left, top, right, bottom))
        return rectangles

    def manual_grid(
        self,
        rows: int,
        columns: int,
        outer_margin: int = 0,
        gutter_x: int = 0,
        gutter_y: int = 0,
        crop_inset: int = 0,
    ) -> GridDetection:
        rows = max(1, rows)
        columns = max(1, columns)
        outer_margin = max(0, outer_margin)
        gutter_x = max(0, gutter_x)
        gutter_y = max(0, gutter_y)
        crop_inset = max(0, crop_inset)

        usable_width = self.width - 2 * outer_margin - (columns - 1) * gutter_x
        usable_height = self.height - 2 * outer_margin - (rows - 1) * gutter_y
        if usable_width <= columns or usable_height <= rows:
            raise ValueError("Margins and gutters leave no usable image area.")
        cell_width = usable_width / columns
        cell_height = usable_height / rows
        rectangles: list[CropRect] = []
        for row in range(rows):
            for column in range(columns):
                left = round(outer_margin + column * (cell_width + gutter_x)) + crop_inset
                top = round(outer_margin + row * (cell_height + gutter_y)) + crop_inset
                right = round(outer_margin + column * (cell_width + gutter_x) + cell_width) - crop_inset
                bottom = round(outer_margin + row * (cell_height + gutter_y) + cell_height) - crop_inset
                if right <= left or bottom <= top:
                    raise ValueError("The crop inset is too large for these cells.")
                rectangles.append(CropRect(left, top, right, bottom))
        return GridDetection(rows, columns, tuple(rectangles), 1.0, method="manual")

    def crop(self, detection: GridDetection, index: int) -> Image.Image:
        rectangle = detection.rectangles[index]
        return self.image.crop(rectangle.as_pillow_box())



def inset_detection(
    detection: GridDetection,
    left: int = 0,
    top: int = 0,
    right: int = 0,
    bottom: int = 0,
) -> GridDetection:
    left = max(0, left)
    top = max(0, top)
    right = max(0, right)
    bottom = max(0, bottom)
    rectangles: list[CropRect] = []
    for rectangle in detection.rectangles:
        adjusted = CropRect(
            rectangle.left + left,
            rectangle.top + top,
            rectangle.right - right,
            rectangle.bottom - bottom,
        )
        if adjusted.width < 4 or adjusted.height < 4:
            raise ValueError("The trim values remove the entire crop.")
        rectangles.append(adjusted)
    return GridDetection(
        detection.rows,
        detection.columns,
        tuple(rectangles),
        detection.confidence,
        detection.method,
    )



def format_crop_for_cts(
    crop: Image.Image,
    mode: str = "cts_card",
    target_size: tuple[int, int] = (480, 830),
) -> Image.Image:
    """Convert a mathematically correct cell into spectator-safe Cubical Compare artwork.

    Generated sheets often contain square cells, while the card artwork area is
    tall. A raw cover-fit cuts away most of the subject. The default keeps the
    complete panel, places it over a soft full-frame background, and emits the
    exact artwork aspect used by Cubical Compare. No labels or recognition markers are added.
    """
    crop = crop.convert("RGB")
    if mode == "original":
        return crop

    # Artwork generated at CTS card length is already composed correctly. Preserve it
    # directly instead of independently adding blurred backgrounds to every card, which
    # would break a scene that is meant to continue across card boundaries.
    target_aspect = target_size[0] / target_size[1]
    crop_aspect = crop.width / max(1, crop.height)
    if abs(math.log(max(1e-9, crop_aspect / target_aspect))) <= 0.004:
        if crop.size == target_size:
            return crop.copy()
        return crop.resize(target_size, Image.Resampling.LANCZOS)

    if mode == "cover":
        return ImageOps.fit(crop, target_size, method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))

    background = ImageOps.fit(crop, target_size, method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))
    background = background.filter(ImageFilter.GaussianBlur(max(10, target_size[0] // 24)))
    background = ImageEnhance.Brightness(background).enhance(0.52)
    foreground = ImageOps.contain(crop, target_size, method=Image.Resampling.LANCZOS)
    x = (target_size[0] - foreground.width) // 2
    y = (target_size[1] - foreground.height) // 2
    background.paste(foreground, (x, y))
    return background

def _safe_slug(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")
    return slug[:64] or "card"


def export_sheet_crops(
    source_path: str | Path,
    output_directory: str | Path,
    detection: GridDetection,
    cards: list[Card],
    start_card: int = 0,
    create_missing: bool = True,
    fit_mode: str = "cts_card",
    target_size: tuple[int, int] = (480, 830),
    progress: Callable[[int, int], None] | None = None,
    cancel_check: Callable[[], bool] | None = None,
) -> tuple[list[Card], list[Path]]:
    source = Path(source_path)
    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    processor = ImageSheetProcessor.from_path(source, cancel_check=cancel_check)
    updated = copy.deepcopy(cards)
    paths: list[Path] = []

    source_copy = output / f"_source{source.suffix.lower() or '.png'}"
    if source.resolve() != source_copy.resolve():
        shutil.copy2(source, source_copy)

    total_rectangles = len(detection.rectangles)
    for local_index, rectangle in enumerate(detection.rectangles):
        if cancel_check is not None and cancel_check():
            raise RuntimeError("Image-sheet import cancelled.")
        card_index = start_card + local_index
        if card_index >= len(updated):
            if not create_missing:
                break
            updated.append(Card(title=f"Card {card_index + 1}"))
        title = updated[card_index].title or f"card_{card_index + 1}"
        filename = f"{card_index:03d}_{_safe_slug(title)}.png"
        destination = output / filename
        crop = processor.image.crop(rectangle.as_pillow_box())
        crop = format_crop_for_cts(crop, fit_mode, target_size)
        crop.save(destination, "PNG", compress_level=3)
        updated[card_index].image = str(destination.resolve())
        paths.append(destination)
        if progress is not None:
            progress(local_index + 1, total_rectangles)

    manifest = {
        "source": str(source.resolve()),
        "rows": detection.rows,
        "columns": detection.columns,
        "start_card": start_card + 1,
        "fit_mode": fit_mode,
        "target_size": list(target_size),
        "files": [path.name for path in paths],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return updated, paths


