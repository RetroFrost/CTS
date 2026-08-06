from __future__ import annotations

from argparse import Namespace
from pathlib import Path

from PIL import Image, ImageChops

from ccengine.models import Card, Project
from ccengine.renderer import FrameRenderer
from ccengine.timing import frame_to_seconds, seconds_to_frame, total_frame_count
from engine_cli import command_render_preview, command_validate, write_ccx


def test_frame_seconds_conversion_is_stable_at_60_fps() -> None:
    project = Project(cards=[Card("One", "1")])
    for frame in (0, 1, 59, 60, 119, 120, total_frame_count(project) - 1):
        assert seconds_to_frame(project, frame_to_seconds(project, frame)) == frame


def test_render_preview_can_address_exact_source_frame(tmp_path: Path) -> None:
    project = Project(cards=[Card("One", "1"), Card("Two", "2")])
    source = tmp_path / "project.ccx"
    output = tmp_path / "frame-120.png"
    write_ccx(project, source)

    command_render_preview(Namespace(
        input=str(source),
        output=str(output),
        time=0.0,
        frame=120,
        width=0,
        height=0,
    ))

    expected = FrameRenderer().render_output_frame(project, 2.0)
    with Image.open(output) as actual:
        actual = actual.convert("RGB")
        assert actual.size == (1920, 1080)
        assert ImageChops.difference(actual, expected).getbbox() is None
