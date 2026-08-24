from __future__ import annotations

import math

from PIL import Image

from ccengine import FrameRenderer
from ccengine import renderer as base
from ccengine.models import Card, Project


def area_scale(affine: tuple[float, float, float, float, float, float]) -> float:
    a, b, c, d, _tx, _ty = affine
    return math.sqrt(abs(a * d - b * c))


def test_public_renderer_has_no_opening_bounce() -> None:
    renderer = FrameRenderer()
    for frame in range(0, 201):
        age = base.age_opening_badge_age(frame)
        assert renderer._opening_entry_affine(frame, age) == (
            1.0,
            0.0,
            0.0,
            1.0,
            0.0,
            0.0,
        )


def test_opening_badge_shell_is_present_and_stationary_from_card_start() -> None:
    class CaptureRenderer(FrameRenderer):
        def __init__(self) -> None:
            super().__init__()
            self.affines: list[tuple[float, float, float, float, float, float]] = []

        def _warp_badge(self, canvas, source, card_x, affine) -> None:
            del canvas, source, card_x
            self.affines.append(affine)

    canvas = Image.new("RGB", (1920, 1080))
    opening = CaptureRenderer()
    opening_project = Project(cards=[Card(value="OPENING")])
    for frame in (100, 120, 180):
        opening._draw_badge(canvas, opening_project, 0, 0.0, frame, [100])
    assert len(opening.affines) == 3
    assert opening.affines[0] == opening.affines[1] == opening.affines[2]



def test_later_badge_vertical_fall_is_preserved() -> None:
    class CaptureRenderer(FrameRenderer):
        def __init__(self) -> None:
            super().__init__()
            self.affines: list[tuple[float, float, float, float, float, float]] = []

        def _warp_badge(self, canvas, source, card_x, affine) -> None:
            del canvas, source, card_x
            self.affines.append(affine)

    canvas = Image.new("RGB", (1920, 1080))
    renderer = CaptureRenderer()
    project = Project(cards=[Card(value=str(index)) for index in range(5)])
    starts = [0, 100, 200, 300, 400]

    renderer._draw_badge(canvas, project, 4, 0.0, 521, starts)
    assert renderer.affines == []

    for frame in (522, 550, 625):
        renderer._draw_badge(canvas, project, 4, 0.0, frame, starts)
    assert len(renderer.affines) == 3
    assert renderer.affines[0][5] < renderer.affines[1][5] < renderer.affines[2][5]


def test_later_badges_never_grow_to_highlight_themselves() -> None:
    renderer = FrameRenderer()
    starts = [0, 120, 240, 360, 480]
    scales = {
        renderer._reference_later_scale(4, frame, starts)
        for frame in (480, 522, 550, 625, 720, 900)
    }
    assert scales == {1.0}


def test_badge_header_value_and_unit_use_three_fixed_lines() -> None:
    renderer = FrameRenderer()
    layout = renderer._text_layout(Card(value="15 YEARS", badge_header="SURVIVE"))
    assert layout == [
        ("SURVIVE", 110.0, 44),
        ("15", 215.0, 112),
        ("YEARS", 310.0, 44),
    ]
