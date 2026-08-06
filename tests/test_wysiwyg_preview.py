from __future__ import annotations

from argparse import Namespace
from pathlib import Path

from PIL import Image, ImageChops

from ccengine.models import Card, Project
from ccengine.renderer import FrameRenderer
from ccengine.validation import normalize_project
from engine_cli import command_render_preview, write_ccx


def test_preview_uses_exact_export_frame_path_and_model_geometry(tmp_path: Path) -> None:
    project = Project(cards=[
        Card("One", "1", "First", ""),
        Card("Two", "2", "Second", ""),
        Card("Three", "3", "", ""),
        Card("Four", "4", "Fourth", ""),
        Card("Five", "5", "Continuous", ""),
    ])
    # Legacy or manipulated project dimensions cannot alter official model
    # geometry. Preview and export must both render the canonical frame.
    project.settings.width = 1280
    project.settings.height = 720
    normalize_project(project)

    source = tmp_path / "project.ccx"
    preview = tmp_path / "preview.png"
    write_ccx(project, source)

    command_render_preview(Namespace(
        input=str(source),
        output=str(preview),
        time=9.75,
        width=0,
        height=0,
    ))

    expected = FrameRenderer().render_output_frame(project, 9.75)
    with Image.open(preview) as actual:
        actual = actual.convert("RGB")
        assert actual.size == (1920, 1080)
        assert ImageChops.difference(actual, expected).getbbox() is None
