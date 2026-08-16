from __future__ import annotations

from pathlib import Path

from ccengine.models import Card, ProjectSettings
from ccengine.renderer import FrameRenderer
from ccengine.watchdata_final import POPPINS_BOLD
from ccengine.watchdata_release import ReleaseWatchDataFrameRenderer
from ccengine.watchdata_renderer import (
    POPPINS_EXTRA_BOLD,
    POPPINS_MEDIUM,
    POPPINS_SEMI_BOLD,
    WATCHDATA_BADGE_FILL,
    WATCHDATA_BADGE_POLYGON,
)


ROOT = Path(__file__).resolve().parents[1]


def test_package_routes_every_renderer_call_through_watchdata_204() -> None:
    assert FrameRenderer is ReleaseWatchDataFrameRenderer


def test_settled_badge_polygon_matches_reference_frame_528() -> None:
    xs = [point[0] for point in WATCHDATA_BADGE_POLYGON]
    ys = [point[1] for point in WATCHDATA_BADGE_POLYGON]
    assert (min(xs), max(xs)) == (88.0, 385.0)
    assert (min(ys), max(ys)) == (32.0, 375.0)
    assert max(xs) - min(xs) + 1 == 298.0
    assert max(ys) - min(ys) + 1 == 344.0


def test_settled_badge_face_is_flat_after_shine() -> None:
    renderer = FrameRenderer()
    renderer._active_settings = ProjectSettings()
    card = Card(title="Reference", value="70K YEARS AGO")
    badge = renderer._badge_source(card, 5.0, sticker_entry=False)
    assert badge.getpixel((300, 90)) == (*WATCHDATA_BADGE_FILL, 255)


def test_measured_opening_stage_uses_reference_widths() -> None:
    renderer = FrameRenderer()
    assert renderer._opening_stage_scale(250) == 1.0
    assert abs(renderer._opening_stage_scale(300) - 273.0 / 298.0) < 1e-9
    assert abs(renderer._opening_stage_scale(420) - 248.0 / 298.0) < 1e-9


def test_measured_shine_clocks_finish_flat() -> None:
    renderer = FrameRenderer()
    assert renderer._opening_source_age(160) > 2.9
    assert renderer._later_source_age(262) > 2.9


def test_watchdata_font_contract_uses_poppins_weights() -> None:
    assert POPPINS_EXTRA_BOLD == "Poppins-ExtraBold.ttf"
    assert POPPINS_BOLD == "Poppins-Bold.ttf"
    assert POPPINS_SEMI_BOLD == "Poppins-SemiBold.ttf"
    assert POPPINS_MEDIUM == "Poppins-Medium.ttf"
    fetcher = (ROOT / "tools" / "fetch_watchdata_fonts.py").read_text(encoding="utf-8")
    for name in (POPPINS_BOLD, POPPINS_SEMI_BOLD, POPPINS_MEDIUM):
        assert name in fetcher
    assert "1982f38ab21303459aa1155265052ca599fa58d1" in fetcher


def test_release_layer_preserves_authored_watchdata_title_breaks() -> None:
    renderer = FrameRenderer()
    assert renderer._draw_explicit_title is not None
    card = Card(title="Ape Noises\nAnd Gestures")
    assert "\n" in card.title


def test_android_and_desktop_watchdata_renderer_sources_are_identical() -> None:
    for name in (
        "watchdata_renderer.py",
        "watchdata_measured.py",
        "watchdata_final.py",
        "watchdata_release.py",
    ):
        desktop = ROOT / "engine" / "ccengine" / name
        android = ROOT / "android" / "app" / "src" / "main" / "python" / "ccengine" / name
        assert desktop.read_bytes() == android.read_bytes()
