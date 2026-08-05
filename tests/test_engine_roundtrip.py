from __future__ import annotations

from pathlib import Path

from PIL import Image

import engine_cli
from ccengine.models import Project


def test_new_open_save_and_preview_roundtrip(tmp_path: Path) -> None:
    ccx = tmp_path / "project.ccx"
    project_json = tmp_path / "project.json"
    png = tmp_path / "preview.png"
    bmp = tmp_path / "preview.bmp"

    engine_cli.write_ccx(engine_cli.make_default_project(), ccx)
    loaded = engine_cli.read_ccx(ccx)
    assert loaded.name == ""
    assert len(loaded.cards) == 1
    assert loaded.cards[0].title == "Card 1"

    loaded.save(project_json)
    reopened = Project.load(project_json)
    assert reopened.cards[0].value == "1"

    renderer = engine_cli.FrameRenderer()
    renderer.render(reopened, 0.0, (960, 540)).save(png)
    renderer.render(reopened, 2.2, (800, 450)).save(bmp)
    for path in (png, bmp):
        with Image.open(path) as image:
            image.load()
            assert image.width > 0 and image.height > 0
        assert path.stat().st_size > 1024
