from __future__ import annotations

from ccengine.model_registry import MODEL_WHAT_MALES_LEARN
from ccengine.models import Card, Project, ProjectSettings
from ccengine.reference_profiles import get_reference_profile
from ccengine.renderer import FrameRenderer


def test_profiles_use_latest_source_pixel_geometry() -> None:
    layout = get_reference_profile(MODEL_WHAT_MALES_LEARN).layout
    assert (layout.slot_pitch, layout.body_inset, layout.body_width) == (477, 8, 469)
    assert (layout.image_height, layout.title_height, layout.description_top) == (872, 92, 965)
    assert layout.divider_width == 1
    assert layout.title_background == (241, 241, 241)
    assert layout.description_background == (99, 94, 91)


def test_title_panel_and_glyphs_render_on_white() -> None:
    project = Project(
        cards=[Card("VISIBLE TITLE", "1", "Description")],
        settings=ProjectSettings(model_id=MODEL_WHAT_MALES_LEARN),
    )
    image = FrameRenderer().render(project, 119 / 60)
    assert image.getpixel((24, 900)) == (241, 241, 241)
    crop = image.crop((40, 865, 430, 935))
    assert crop.convert("L").getextrema()[0] < 60


def test_empty_description_reflows_artwork_without_blank_gap() -> None:
    project = Project(cards=[Card("Title only", "1", "")])
    output = FrameRenderer().render(project, 119 / 60)
    assert output.getpixel((24, 980)) != (0, 0, 0)
    assert output.getpixel((24, 1000)) == (241, 241, 241)
