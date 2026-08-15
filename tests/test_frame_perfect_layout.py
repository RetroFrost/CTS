from __future__ import annotations

from ccengine.model_registry import MODEL_TYPES_OF_RELATIONSHIPS, MODEL_WHAT_MALES_LEARN
from ccengine.models import Card, Project, ProjectSettings
from ccengine.reference_profiles import get_reference_profile
from ccengine.renderer import FrameRenderer


def test_profiles_use_source_pixel_geometry() -> None:
    males = get_reference_profile(MODEL_WHAT_MALES_LEARN).layout
    relationships = get_reference_profile(MODEL_TYPES_OF_RELATIONSHIPS).layout

    assert (males.slot_pitch, males.body_inset, males.body_width) == (477, 9, 471)
    assert (males.image_height, males.title_height, males.description_top) == (872, 93, 965)
    assert males.title_background == (242, 242, 242)

    assert (relationships.slot_pitch, relationships.body_width) == (480, 475)
    assert (relationships.image_height, relationships.title_height, relationships.description_top) == (788, 118, 916)
    assert relationships.title_background == (244, 242, 240)
    assert relationships.divider_color == (213, 126, 0)


def test_title_panel_and_glyphs_render_for_both_models() -> None:
    for model_id, frame, background, title_y in (
        (MODEL_WHAT_MALES_LEARN, 119, (242, 242, 242), 900),
        (MODEL_TYPES_OF_RELATIONSHIPS, 520, (244, 242, 240), 840),
    ):
        project = Project(
            cards=[Card("VISIBLE TITLE", "1", "Description")],
            settings=ProjectSettings(model_id=model_id),
        )
        image = FrameRenderer().render(project, frame / 60)
        # A clean corner of the title strip must retain the model-owned panel.
        assert image.getpixel((24, title_y)) == background
        # The central title region must contain dark glyph pixels as well.
        crop = image.crop((40, title_y - 35, 430, title_y + 35))
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
