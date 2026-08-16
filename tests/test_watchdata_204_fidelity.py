from __future__ import annotations

from pathlib import Path

from ccengine.models import Card, ProjectSettings
from ccengine.renderer import FrameRenderer
from ccengine.watchdata_renderer import (
    POPPINS_EXTRA_BOLD,
    POPPINS_MEDIUM,
    POPPINS_SEMI_BOLD,
    WATCHDATA_BADGE_FILL,
    WATCHDATA_BADGE_POLYGON,
    WatchDataFrameRenderer,
)


ROOT = Path(__file__).resolve().parents[1]


def test_package_routes_every_renderer_call_through_watchdata_204() -> None:
    assert FrameRenderer is WatchDataFrameRenderer


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
    # This sample is inside the upper-right red face and outside both text
    # lines, shadow and outline. A cached shine/streak would make it lighter.
    assert badge.getpixel((300, 90)) == (*WATCHDATA_BADGE_FILL, 255)


def test_watchdata_font_contract_uses_poppins_weights() -> None:
    assert POPPINS_EXTRA_BOLD == "Poppins-ExtraBold.ttf"
    assert POPPINS_SEMI_BOLD == "Poppins-SemiBold.ttf"
    assert POPPINS_MEDIUM == "Poppins-Medium.ttf"
    spec = (ROOT / "engine" / "cubical-compare-engine.spec").read_text(encoding="utf-8")
    gradle = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    for name in (POPPINS_EXTRA_BOLD, POPPINS_SEMI_BOLD, POPPINS_MEDIUM):
        assert name in spec
        assert name in gradle
    assert "167667d203d98f5b27c3ff58d486eea9c5287fe4" in spec
    assert "167667d203d98f5b27c3ff58d486eea9c5287fe4" in gradle


def test_android_and_desktop_watchdata_renderer_source_are_identical() -> None:
    desktop = ROOT / "engine" / "ccengine" / "watchdata_renderer.py"
    android = ROOT / "android" / "app" / "src" / "main" / "python" / "ccengine" / "watchdata_renderer.py"
    assert desktop.read_bytes() == android.read_bytes()
