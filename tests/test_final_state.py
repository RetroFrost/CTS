from __future__ import annotations

from pathlib import Path
import math

import pytest
from PIL import Image

from ccengine.assets import collect_project_assets
from ccengine.exporter import VideoExporter
from ccengine.image_sheet_core import CropRect, GridDetection, export_sheet_crops
from ccengine.importers import load_spreadsheet
from ccengine.models import Card, Project
from ccengine.renderer import FrameRenderer
from ccengine.timing import minimum_duration, total_duration
from ccengine.validation import normalize_project
from engine_cli import read_ccx


def test_csv_preserves_zero_false_and_first_data_row(tmp_path: Path) -> None:
    source = tmp_path / "data.csv"
    source.write_text("Age,0,Zero,false.png\nNext,False,Second,next.png\n", encoding="utf-8")
    cards = load_spreadsheet(source)
    assert len(cards) == 2
    assert cards[0].title == "Age"
    assert cards[0].value == "0"
    assert cards[1].value == "False"
    assert cards[0].image == str((tmp_path / "false.png").resolve())


def test_real_header_still_maps_columns(tmp_path: Path) -> None:
    source = tmp_path / "data.csv"
    source.write_text("Title,Value,Description,Image\nCard,0,Kept,image.png\n", encoding="utf-8")
    cards = load_spreadsheet(source)
    assert len(cards) == 1
    assert (cards[0].title, cards[0].value, cards[0].description) == ("Card", "0", "Kept")


def test_image_sheet_preserves_transform_and_creates_extra(tmp_path: Path) -> None:
    sheet = tmp_path / "sheet.png"
    Image.new("RGB", (40, 20), "red").save(sheet)
    detection = GridDetection(1, 2, (CropRect(0, 0, 20, 20), CropRect(20, 0, 40, 20)), 1.0, "manual")
    original = Card("One", "1", image_x=22, image_y=-7, image_scale=1.5, image_rotation=15,
                    image_crop_left=.1, image_layer="front")
    cards, paths = export_sheet_crops(sheet, tmp_path / "assets", detection, [original], create_missing=True)
    assert len(cards) == len(paths) == 2
    assert cards[0].image_x == 22
    assert cards[0].image_scale == 1.5
    assert cards[0].image_layer == "front"


def test_custom_length_cannot_stretch_or_truncate_locked_model() -> None:
    project = Project(cards=[Card("One", "1")])
    project.settings.auto_length = False
    project.settings.custom_length_seconds = 1
    normalize_project(project)

    assert project.settings.auto_length is True
    assert total_duration(project) == pytest.approx(minimum_duration(project))
    # The legacy value may survive as unused project data, but it cannot alter
    # a locked model's integer-frame timeline.
    assert project.settings.custom_length_seconds == pytest.approx(1.0)


def test_corrupt_or_unsafe_project_is_rejected_or_normalized(tmp_path: Path) -> None:
    bad = tmp_path / "bad.ccx"
    bad.write_text("CCX1\nproject.name=!!!\ncards.count=1\n", encoding="utf-8")
    with pytest.raises(ValueError, match="Corrupted"):
        read_ccx(bad)

    project = Project(cards=[Card("Unsafe", "1", image_scale=999, image_x=math.inf)])
    project.settings.width = 1919
    project.settings.height = 1079
    project.settings.encoder_preset = "typo"
    normalize_project(project)
    assert (project.settings.width, project.settings.height) == (1920, 1080)
    assert project.settings.fps == 60
    assert project.settings.encoder_preset == "faster"
    assert project.cards[0].image_scale == 8
    assert math.isfinite(project.cards[0].image_x)


def test_portable_save_collects_assets(tmp_path: Path) -> None:
    image = tmp_path / "source.png"
    soundtrack = tmp_path / "music.wav"
    Image.new("RGB", (4, 4), "blue").save(image)
    soundtrack.write_bytes(b"RIFF" + b"0" * 64)
    target = tmp_path / "portable.json"
    project = Project(cards=[Card("One", "1", image=str(image))])
    project.settings.soundtrack = str(soundtrack)
    portable = collect_project_assets(project, target)
    portable.save(target)
    reopened = Project.load(target)
    assert Path(reopened.cards[0].image).is_file()
    assert Path(reopened.settings.soundtrack).is_file()


def test_renderer_caches_are_bounded(tmp_path: Path) -> None:
    renderer = FrameRenderer()
    for index in range(90):
        renderer._active_settings = Project().settings
        renderer._body_cache[(index,)] = Image.new("RGB", (2, 2))
        while len(renderer._body_cache) > renderer._max_body_cache:
            renderer._body_cache.popitem(last=False)
    assert len(renderer._body_cache) <= 32


def test_missing_soundtrack_is_an_export_error(tmp_path: Path) -> None:
    project = Project(cards=[Card("One", "1")])
    project.settings.soundtrack = str(tmp_path / "missing.mp3")
    with pytest.raises(FileNotFoundError):
        VideoExporter().export(project, tmp_path / "out.mp4")


def test_native_windows_exposes_complete_workflow_and_async_import() -> None:
    source = (Path(__file__).parents[1] / "native/windows/main.cpp").read_text(encoding="utf-8")
    assert 'L"Music",ID_MUSIC' in source
    assert 'L"Model",ID_MODEL' in source
    assert 'What Males Learn At Each Age' in source
    assert 'Types Of Relationships' in source
    assert 'Automatic video length' not in source
    assert 'Fixed length (seconds)' not in source
    assert 'TaskKind::ImportData' in source
    assert 'save-portable' in source
    assert '"--create-extra"' in source
    assert '"--fast"' not in source[source.index("void begin_export"):source.index("void show_page")]
