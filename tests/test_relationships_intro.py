from __future__ import annotations

from PIL import ImageChops

from ccengine.brand_intro import (
    INTRO_FRAME_COUNT,
    INTRO_OVERLAY_END_FRAME,
    intro_signature,
    render_relationships_intro,
    render_relationships_intro_overlay,
)
from ccengine.model_registry import MODEL_TYPES_OF_RELATIONSHIPS, MODEL_WHAT_MALES_LEARN
from ccengine.models import Card, Project, ProjectSettings
from ccengine.renderer import FrameRenderer
from ccengine.timing import frame_to_seconds
from ccengine.validation import normalize_project


def project(model_id: str) -> Project:
    value = Project(
        cards=[Card("First", "1")],
        settings=ProjectSettings(model_id=model_id),
    )
    return normalize_project(value)


def test_intro_has_exact_locked_lead_and_overlap_bounds() -> None:
    assert INTRO_FRAME_COUNT == 374
    assert INTRO_OVERLAY_END_FRAME == 550


def test_intro_key_phases_are_deterministic() -> None:
    assert intro_signature(0) == ((1920, 1080), None)
    for frame in (30, 60, 90, 150, 240, 270, 330, 373):
        size, bounds = intro_signature(frame)
        assert size == (1920, 1080)
        assert bounds is not None


def test_relationships_uses_intro_while_age_model_starts_cards() -> None:
    renderer = FrameRenderer()
    relationships = project(MODEL_TYPES_OF_RELATIONSHIPS)
    ages = project(MODEL_WHAT_MALES_LEARN)

    relationship_frame = renderer.render_output_frame(
        relationships, frame_to_seconds(relationships, 120)
    )
    expected_intro = render_relationships_intro(120)
    assert ImageChops.difference(relationship_frame, expected_intro).getbbox() is None

    age_frame = renderer.render_output_frame(ages, 0.0)
    assert ImageChops.difference(age_frame, render_relationships_intro(0)).getbbox() is not None


def test_first_card_begins_at_frame_374_without_cutting_away_logo() -> None:
    renderer = FrameRenderer()
    relationships = project(MODEL_TYPES_OF_RELATIONSHIPS)

    before = renderer.render_output_frame(
        relationships, frame_to_seconds(relationships, 373)
    )
    first_card = renderer.render_output_frame(
        relationships, frame_to_seconds(relationships, 374)
    )
    assert ImageChops.difference(before, first_card).getbbox() is not None

    overlay = render_relationships_intro_overlay(374)
    assert overlay.getchannel("A").getbbox() is not None


def test_logo_tail_holds_then_fades_over_first_card() -> None:
    full = render_relationships_intro_overlay(420).getchannel("A")
    fading = render_relationships_intro_overlay(520).getchannel("A")
    gone = render_relationships_intro_overlay(550).getchannel("A")

    assert full.getbbox() is not None
    assert fading.getbbox() is not None
    assert gone.getbbox() is None
    assert sum(fading.getdata()) < sum(full.getdata())
