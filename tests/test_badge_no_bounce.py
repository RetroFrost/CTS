from __future__ import annotations

import math

from PIL import Image, ImageChops

from ccengine import FrameRenderer
from ccengine import renderer as base
from ccengine.models import Card, Project
from ccengine.timing import card_start_frames


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
    assert len(scales) == 1
    assert math.isclose(scales.pop(), 325.0 / 298.0)


def test_opening_badges_also_keep_one_size() -> None:
    renderer = FrameRenderer()
    starts = [0, 120, 240, 360]
    scales = {
        renderer._reference_opening_scale(index, frame, frame - starts[index], starts)
        for frame in (360, 480, 589, 650, 720)
        for index in range(4)
        if frame >= starts[index]
    }
    assert len(scales) == 1
    assert math.isclose(scales.pop(), 325.0 / 298.0)


def test_badge_header_value_and_unit_use_three_fixed_lines() -> None:
    renderer = FrameRenderer()
    layout = renderer._text_layout(Card(value="15 YEARS", badge_header="SURVIVE"))
    assert layout == [
        ("SURVIVE", 110.0, 36),
        ("15", 215.0, 94),
        ("YEARS", 310.0, 38),
    ]


def test_legacy_scale_paths_cannot_shrink_badges_over_time() -> None:
    renderer = FrameRenderer()
    expected = 325.0 / 298.0
    starts = [0, 120, 240, 360, 480, 600]

    assert base.BADGE_ACTIVE_SCALE == expected
    assert base.BADGE_MEDIUM_SCALE == expected
    assert base.BADGE_SMALL_SCALE == expected
    assert {
        renderer._stage_scale(0, frame / 60.0, [value / 60.0 for value in starts])
        for frame in (0, 240, 480, 720, 1200)
    } == {expected}
    assert {
        renderer._age_deemphasis_scale(0, frame, starts)
        for frame in (0, 240, 480, 720, 1200)
    } == {expected}


def test_rendered_badge_pixel_bounds_stay_constant_across_timeline() -> None:
    project = Project(
        cards=[
            Card(title=f"Card {index}", value=f"{20 - index} YEARS", badge_header="SURVIVE")
            for index in range(8)
        ]
    )
    renderer = FrameRenderer()
    renderer.render(project, 0.0)
    starts = card_start_frames(project)
    bounds: set[tuple[int, int]] = set()

    for index, local_frame in ((0, 200), (0, 800), (4, 230), (4, 800), (7, 230), (7, 800)):
        canvas = Image.new("RGB", (1920, 1080), (0, 0, 0))
        renderer._draw_badge(
            canvas,
            project,
            index,
            500.0,
            starts[index] + local_frame,
            starts,
        )
        red, green, _blue = canvas.split()
        red_dominance = ImageChops.subtract(red, green.point(lambda value: min(255, value * 2)))
        bbox = red_dominance.getbbox()
        assert bbox is not None
        bounds.add((bbox[2] - bbox[0], bbox[3] - bbox[1]))

    assert bounds == {(325, 375)}
