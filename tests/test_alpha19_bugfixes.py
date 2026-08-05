from __future__ import annotations

from pathlib import Path

import pytest
from PIL import Image

import engine_cli
from ccengine.image_sheet_core import ImageSheetProcessor, export_sheet_crops
from ccengine.models import Card, Project


def test_card_ids_survive_ccx_and_json_roundtrips(tmp_path: Path) -> None:
    project = Project(cards=[Card("Card 1", "1"), Card("", "2")])
    ids = [card.id for card in project.cards]
    assert len(set(ids)) == 2

    ccx = tmp_path / "stable.ccx"
    engine_cli.write_ccx(project, ccx)
    reopened = engine_cli.read_ccx(ccx)
    assert [card.id for card in reopened.cards] == ids

    json_path = tmp_path / "stable.json"
    reopened.save(json_path)
    from_json = Project.load(json_path)
    assert [card.id for card in from_json.cards] == ids


def test_image_sheet_reports_progress_preserves_ids_and_can_cancel(tmp_path: Path) -> None:
    sheet = Image.new("RGB", (200, 200), "white")
    for y in range(2):
        for x in range(2):
            colour = (40 + x * 120, 40 + y * 120, 100)
            for yy in range(y * 100, (y + 1) * 100):
                for xx in range(x * 100, (x + 1) * 100):
                    sheet.putpixel((xx, yy), colour)
    source = tmp_path / "sheet.png"
    sheet.save(source)

    processor = ImageSheetProcessor.from_path(source)
    detection = processor.manual_grid(2, 2, 0, 0, 0, 0)
    cards = [Card(f"Card {i + 1}", str(i + 1)) for i in range(4)]
    ids = [card.id for card in cards]
    updates: list[tuple[int, int]] = []

    updated, paths = export_sheet_crops(
        source,
        tmp_path / "assets",
        detection,
        cards,
        create_missing=False,
        progress=lambda done, total: updates.append((done, total)),
    )
    assert len(paths) == 4
    assert updates[-1] == (4, 4)
    assert [card.id for card in updated] == ids

    with pytest.raises(RuntimeError, match="cancelled"):
        export_sheet_crops(
            source,
            tmp_path / "cancelled",
            detection,
            cards,
            cancel_check=lambda: True,
        )


def test_native_ui_bugfix_contract() -> None:
    root = Path(__file__).resolve().parents[1]
    linux = (root / "native/linux-gtk/main.cpp").read_text(encoding="utf-8")
    windows = (root / "native/windows/main.cpp").read_text(encoding="utf-8")

    fields_block = linux.split("void fields_changed", 1)[1].split("void card_selected", 1)[0]
    assert "rebuild_card_list" not in fields_block
    assert 'g_object_set_data_full(G_OBJECT(row), "cubical-card-id"' in linux
    assert "erase_card_by_id" in linux
    assert "erase_card_by_id" in windows
    assert 'g_object_get_data(G_OBJECT(row), "cubical-card-id")' in linux
    assert "LB_GETCURSEL" in windows

    for source in (linux, windows):
        assert "--progress-file" in source
        assert "--cancel-file" in source
        assert "Importing image sheet" in source
        assert "Rendering frame " in source
        assert "valid_mp4_file" in source
        assert "Export failed: no usable MP4 was created." in source

    assert "gtk_window_set_modal(GTK_WINDOW(s->task_window), TRUE)" in linux
    assert "gtk_window_present(GTK_WINDOW(s->task_window))" in linux
    assert "PROGDLG_MODAL" in windows
    assert "PROGDLG_NOMINIMIZE" in windows
