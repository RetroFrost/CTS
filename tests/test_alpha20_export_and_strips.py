from __future__ import annotations

from pathlib import Path
import shutil
import math

import pytest
from PIL import Image, ImageChops

from ccengine.exporter import ExportCancelled, VideoExporter
from ccengine.image_sheet_core import ImageSheetProcessor, export_sheet_crops
from ccengine.models import Card, Project, ProjectSettings
from ccengine.timing import total_duration


def tiny_export_project() -> Project:
    settings = ProjectSettings(
        width=160,
        height=90,
        fps=2,
        auto_length=True,
        credits_enabled=False,
        encoder_preset="ultrafast",
        encoder_crf=30,
    )
    return Project(cards=[Card("Card", "1", "Real export", "")], settings=settings)


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="FFmpeg is required")
def test_export_creates_a_real_mp4_and_reports_every_frame(tmp_path: Path) -> None:
    output = tmp_path / "real-video.mp4"
    progress: list[tuple[int, int]] = []

    VideoExporter().export(
        tiny_export_project(),
        output,
        progress=lambda done, total: progress.append((done, total)),
    )

    assert output.is_file()
    assert output.stat().st_size >= 256
    assert b"ftyp" in output.read_bytes()[:64]
    total = math.ceil(total_duration(tiny_export_project()) * 2)
    assert progress == [(frame, total) for frame in range(1, total + 1)]
    assert not list(tmp_path.glob(".*.cubical-part-*.mp4"))


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="FFmpeg is required")
def test_cancelled_export_does_not_replace_an_existing_video(tmp_path: Path) -> None:
    output = tmp_path / "keep.mp4"
    original = b"existing destination must remain untouched"
    output.write_bytes(original)

    with pytest.raises(ExportCancelled):
        VideoExporter().export(
            tiny_export_project(),
            output,
            cancel_check=lambda: True,
        )

    assert output.read_bytes() == original
    assert not list(tmp_path.glob(".*.cubical-part-*.mp4"))


def test_exact_cts_length_strip_is_split_without_recomposing_the_scene(tmp_path: Path) -> None:
    card_width, card_height, count = 480, 830, 3
    # A smooth horizontal scene makes any invented gutters or per-card background
    # reconstruction visible in a pixel-exact comparison.
    gradient = Image.linear_gradient("L").rotate(90, expand=True).resize(
        (card_width * count, card_height), Image.Resampling.BILINEAR
    )
    sheet = Image.merge("RGB", (gradient, gradient.transpose(Image.Transpose.FLIP_LEFT_RIGHT), gradient))
    source = tmp_path / "continuous-strip.png"
    sheet.save(source)

    processor = ImageSheetProcessor.from_path(source)
    detection = processor.detect(preferred_count=count)

    assert detection.method == "continuous-cts-strip"
    assert (detection.rows, detection.columns, detection.count) == (1, count, count)
    assert [rect.width for rect in detection.rectangles] == [card_width] * count
    assert [rect.height for rect in detection.rectangles] == [card_height] * count

    cards = [Card("", "?")] + [Card(f"Card {index}", str(index)) for index in range(2, count + 1)]
    updated, paths = export_sheet_crops(
        source,
        tmp_path / "assets",
        detection,
        cards,
        create_missing=False,
        fit_mode="cts_card",
        target_size=(card_width, card_height),
    )

    assert len(updated) == count
    assert len(paths) == count
    for index, path in enumerate(paths):
        expected = sheet.crop((index * card_width, 0, (index + 1) * card_width, card_height))
        with Image.open(path) as actual:
            actual.load()
            assert actual.size == (card_width, card_height)
            assert ImageChops.difference(actual.convert("RGB"), expected).getbbox() is None
