from __future__ import annotations

from argparse import Namespace
from pathlib import Path

from PIL import Image, ImageChops

from ccengine.models import Card, Project
from ccengine.renderer import FrameRenderer
from engine_cli import command_render_preview, write_ccx


def test_preview_uses_exact_export_frame_path(tmp_path: Path) -> None:
    project = Project(cards=[
        Card("One", "1", "First", ""),
        Card("Two", "2", "Second", ""),
        Card("Three", "3", "", ""),
        Card("Four", "4", "Fourth", ""),
        Card("Five", "5", "Continuous", ""),
    ])
    project.settings.width = 1280
    project.settings.height = 720

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
        assert actual.size == (1280, 720)
        assert ImageChops.difference(actual, expected).getbbox() is None
