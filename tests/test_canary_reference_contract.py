from __future__ import annotations

from ccengine import canary_reference as ref
from ccengine.canary_badge_reference import continuous_badge_red_bounds
from ccengine.models import Card, Project
from ccengine.renderer import FrameRenderer


def project57() -> Project:
    return Project(cards=[Card(f"Card {i+1}", f"{i+1}") for i in range(57)])


def test_canary_geometry_is_latest_reference_geometry() -> None:
    assert (ref.WIDTH, ref.HEIGHT, ref.FPS) == (1920, 1080, 60)
    assert (ref.SLOT_PITCH, ref.BODY_WIDTH, ref.BODY_INSET) == (477, 469, 8)
    assert (ref.IMAGE_HEIGHT, ref.TITLE_HEIGHT) == (872, 92)
    assert (ref.DIVIDER_Y, ref.DIVIDER_HEIGHT) == (964, 1)
    assert (ref.DESCRIPTION_TOP, ref.DESCRIPTION_HEIGHT) == (965, 110)
    assert (ref.BOTTOM_BORDER_TOP, ref.BOTTOM_BORDER_HEIGHT) == (1075, 5)


def test_opening_positions_are_direct_source_frame_samples() -> None:
    assert ref.opening_card_x(0, 0) == -470
    assert ref.opening_card_x(0, 30) == -123
    assert ref.opening_card_x(0, 90) == 10
    assert ref.opening_card_x(3, 396) is None
    assert ref.opening_card_x(3, 397) == 1672
    assert ref.opening_card_x(3, 420) == 1495
    assert ref.opening_card_x(3, 527) == 1440


def test_credits_enter_hold_and_exit_on_measured_frames() -> None:
    assert ref.credits_x(0) == 1920
    assert ref.credits_x(20) == 1705
    assert ref.credits_x(90) == 1440
    assert ref.credits_x(360) == 1440
    assert ref.credits_x(400) == 1755
    assert ref.credits_x(440) == 1920


def test_continuous_strip_uses_477_pixel_pitch() -> None:
    assert ref.continuous_body_x(528, 0) == 8
    assert ref.continuous_body_x(660, 0) == -371
    assert [ref.continuous_body_x(660, i) for i in range(5)] == [-371, 106, 583, 1060, 1537]
    assert ref.continuous_body_x(742, 4) == 1356


def test_continuous_badge_geometry_is_frame_addressed() -> None:
    assert ref.continuous_badge_state(160) is None
    assert ref.continuous_badge_state(163) == (324, 372, -258)
    assert ref.continuous_badge_state(200) == (324, 372, 4)
    assert ref.continuous_badge_state(213) == (324, 372, 16)
    assert ref.continuous_badge_state(390) == (306, 350, 28)
    assert ref.continuous_badge_state(600) == (284, 324, 44)
    assert ref.continuous_badge_state(815) == (254, 294, 62)
    assert ref.continuous_badge_state(850) == (248, 286, 66)
    assert ref.continuous_badge_state(999) == (248, 286, 66)


def test_dense_visible_badge_bounds_match_reference_components() -> None:
    assert continuous_badge_red_bounds(200) == (73, 12, 324, 364)
    assert continuous_badge_red_bounds(213) == (73, 20, 325, 366)
    assert continuous_badge_red_bounds(390) == (82, 28, 306, 350)
    assert continuous_badge_red_bounds(600) == (94, 44, 283, 324)
    assert continuous_badge_red_bounds(815) == (108, 62, 254, 292)
    assert continuous_badge_red_bounds(850) == (111, 68, 249, 284)


def _red_bbox_near(image, expected: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    ex, ey, ew, eh = expected
    left = max(0, ex - 20)
    top = max(0, ey - 20)
    right = min(image.width, ex + ew + 20)
    bottom = min(image.height, ey + eh + 20)
    found: list[tuple[int, int]] = []
    for y in range(top, bottom):
        for x in range(left, right):
            r, g, b = image.getpixel((x, y))
            if r > 120 and r > g * 2.2 and r > b * 2.2:
                found.append((x, y))
    assert found
    xs = [p[0] for p in found]
    ys = [p[1] for p in found]
    return min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1


def test_rendered_badge_bounds_land_on_reference_pixels() -> None:
    cards = [Card("", "", "", "") for _ in range(57)]
    cards[4].value = "7M YEARS AGO"
    project = Project(cards=cards)
    renderer = FrameRenderer()

    for global_frame, expected in (
        (918, (1046, 28, 306, 350)),
        (1128, (592, 44, 283, 324)),
    ):
        image = renderer.render(project, global_frame / 60.0)
        actual = _red_bbox_near(image, expected)
        assert all(abs(a - e) <= 2 for a, e in zip(actual, expected)), (global_frame, actual, expected)


def test_canonical_movie_is_exactly_12267_frames() -> None:
    assert ref.content_end_frame(57) == 11858
    assert ref.total_frame_count(57) == 12267
    renderer = FrameRenderer()
    assert renderer.duration(project57()) == 12267 / 60


def test_value_layout_matches_reference_badges() -> None:
    assert FrameRenderer._value_lines("7M YEARS AGO") == ["7M", "YEARS AGO"]
    assert FrameRenderer._value_lines("300K YEARS AGO") == ["300K", "YEARS AGO"]
    assert FrameRenderer._value_lines("8000 BC") == ["8000", "BC"]
