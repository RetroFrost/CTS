from __future__ import annotations

from ccengine.model_registry import MODEL_WHAT_MALES_LEARN
from ccengine.models import Card, Project, ProjectSettings
from ccengine.reference_profiles import get_reference_profile
from ccengine.renderer import FrameRenderer


def test_profiles_use_source_pixel_geometry() -> None:
    males = get_reference_profile(MODEL_WHAT_MALES_LEARN).layout
    assert (males.slot_pitch, males.body_inset, males.body_width) == (476, 9, 471)
    assert (males.image_height, males.title_height, males.description_top) == (872, 93, 965)
    assert males.title_background == (242, 242, 242)



def test_title_panel_and_glyphs_render_on_white() -> None:
    project = Project(
        cards=[Card("VISIBLE TITLE", "1", "Description")],
        settings=ProjectSettings(model_id=MODEL_WHAT_MALES_LEARN),
    )
    image = FrameRenderer().render(project, 119 / 60)
    assert image.getpixel((24, 900)) == (242, 242, 242)
    crop = image.crop((40, 865, 430, 935))
    assert crop.convert("L").getextrema()[0] < 60


def test_empty_description_reflows_artwork_without_blank_gap() -> None:
    project = Project(cards=[Card("Title only", "1", "")])
    renderer = FrameRenderer()
    profile = get_reference_profile(MODEL_WHAT_MALES_LEARN)
    # Public render check: the title-only band is anchored to the bottom at
    # y=987..1079, so the previous description area cannot remain black.
    output = renderer.render(project, 119 / 60)
    assert output.getpixel((24, 980)) != (0, 0, 0)
    assert output.getpixel((24, 1000)) == profile.layout.title_background
