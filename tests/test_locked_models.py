from __future__ import annotations

import pytest

from ccengine.model_registry import (
    DEFAULT_MODEL_ID,
    MODEL_TYPES_OF_RELATIONSHIPS,
    MODEL_WHAT_MALES_LEARN,
    get_model,
    list_models,
    model_manifest,
)
from ccengine.models import Card, Project, ProjectSettings
from ccengine.timing import (
    card_start_frames,
    intro_frame_count,
    total_frame_count,
)
from ccengine.validation import normalize_project


def test_both_reference_models_are_registered_and_locked() -> None:
    models = {model.id: model for model in list_models()}
    assert set(models) == {
        MODEL_TYPES_OF_RELATIONSHIPS,
        MODEL_WHAT_MALES_LEARN,
    }
    assert DEFAULT_MODEL_ID == MODEL_WHAT_MALES_LEARN
    for model in models.values():
        assert model.width == 1920
        assert model.height == 1080
        assert model.fps == 60
        assert model.reference.sha256
        assert "timeline.cadence" in model.locked_fields
        assert "animation.badge" in model.locked_fields
        assert "card.title" in model.editable_fields


def test_reference_video_identities_match_supplied_files() -> None:
    relationships = get_model(MODEL_TYPES_OF_RELATIONSHIPS).reference
    ages = get_model(MODEL_WHAT_MALES_LEARN).reference

    assert relationships.frame_count == 11130
    assert relationships.visual_duration_seconds == pytest.approx(185.5)
    assert relationships.first_content_frame == 374
    assert relationships.sha256 == "ca16b2301cf4c8b35e0957ed612a5894f0ac469485461359ed80c71e1842e6fd"

    assert ages.frame_count == 16741
    assert ages.visual_duration_seconds == pytest.approx(279.01666666666665)
    assert ages.first_content_frame == 0
    assert ages.sha256 == "5e1df47e94fa5dfd5ea9eaa07b75f1fb29a7f74df3bbb11de25c452523722506"


def test_validation_restores_model_owned_output_values() -> None:
    settings = ProjectSettings(
        model_id=MODEL_TYPES_OF_RELATIONSHIPS,
        model_revision=999,
        width=640,
        height=360,
        fps=24,
        auto_length=False,
        custom_length_seconds=1.0,
    )
    project = Project(cards=[Card("A", "1")], settings=settings)
    normalize_project(project)

    assert project.settings.model_revision == 1
    assert project.settings.width == 1920
    assert project.settings.height == 1080
    assert project.settings.fps == 60
    assert project.settings.auto_length is True


def test_relationships_intro_offsets_every_card_by_374_frames() -> None:
    relationships = Project(
        cards=[Card(str(index), str(index)) for index in range(5)],
        settings=ProjectSettings(model_id=MODEL_TYPES_OF_RELATIONSHIPS),
    )
    ages = Project(
        cards=[Card(str(index), str(index)) for index in range(5)],
        settings=ProjectSettings(model_id=MODEL_WHAT_MALES_LEARN),
    )
    normalize_project(relationships)
    normalize_project(ages)

    assert intro_frame_count(relationships) == 374
    assert intro_frame_count(ages) == 0
    assert card_start_frames(relationships) == [374, 494, 614, 734, 914]
    assert card_start_frames(ages) == [0, 120, 240, 360, 540]
    assert total_frame_count(relationships) - total_frame_count(ages) == 374


def test_project_json_contains_model_lock() -> None:
    project = Project(
        name="Reference reproduction",
        cards=[Card("First", "1")],
        settings=ProjectSettings(model_id=MODEL_TYPES_OF_RELATIONSHIPS),
    )
    normalize_project(project)
    payload = project.to_dict()

    assert payload["version"] == 3
    assert payload["settings"]["model_id"] == MODEL_TYPES_OF_RELATIONSHIPS
    assert payload["model_lock"] == {
        "id": MODEL_TYPES_OF_RELATIONSHIPS,
        "revision": 1,
        "renderer_profile": "infinite-comparison-v1",
    }


def test_model_manifest_exposes_user_and_model_owned_fields() -> None:
    manifest = model_manifest(get_model(MODEL_WHAT_MALES_LEARN))
    assert manifest["locked"] is True
    assert manifest["output"] == {"width": 1920, "height": 1080, "fps": 60}
    assert "card.image" in manifest["editable_fields"]
    assert "animation.outro" in manifest["locked_fields"]
