from __future__ import annotations

import pytest

from ccengine.model_registry import (
    DEFAULT_MODEL_ID,
    MODEL_WHAT_MALES_LEARN,
    get_model,
    list_models,
    model_manifest,
)
from ccengine.models import Card, Project, ProjectSettings
from ccengine.timing import card_start_frames, intro_frame_count, total_frame_count
from ccengine.validation import normalize_project


def test_single_reference_model_is_registered_and_locked() -> None:
    models = list_models()
    assert [model.id for model in models] == [MODEL_WHAT_MALES_LEARN]
    assert DEFAULT_MODEL_ID == MODEL_WHAT_MALES_LEARN
    model = models[0]
    assert (model.width, model.height, model.fps) == (1920, 1080, 60)
    assert "timeline.cadence" in model.locked_fields
    assert "animation.badge" in model.locked_fields
    assert "card.title" in model.editable_fields


def test_reference_video_identity_matches_supplied_file() -> None:
    reference = get_model(MODEL_WHAT_MALES_LEARN).reference
    assert reference.frame_count == 12_267
    assert reference.visual_duration_seconds == pytest.approx(204.45)
    assert reference.first_content_frame == 0
    assert reference.sha256 == "965d878c8343f820a66d34129c8a998de6a8039fed110a7f5fc1fd622ee355b2"


def test_validation_restores_model_owned_output_values() -> None:
    project = Project(
        cards=[Card("A", "1")],
        settings=ProjectSettings(
            model_id="removed-model",
            model_revision=999,
            width=640,
            height=360,
            fps=24,
            auto_length=False,
        ),
    )
    normalize_project(project)
    assert project.settings.model_id == MODEL_WHAT_MALES_LEARN
    assert project.settings.model_revision == 1
    assert (project.settings.width, project.settings.height, project.settings.fps) == (1920, 1080, 60)
    assert project.settings.auto_length is False


def test_integer_frame_timeline_uses_contact_sheet_contract() -> None:
    project = Project(cards=[Card(str(index), str(index)) for index in range(57)])
    normalize_project(project)
    assert intro_frame_count(project) == 0
    assert card_start_frames(project)[:5] == [0, 120, 240, 360, 528]
    assert total_frame_count(project) == 12_267


def test_project_json_contains_model_lock() -> None:
    project = Project(name="Reference reproduction", cards=[Card("First", "1")])
    normalize_project(project)
    payload = project.to_dict()
    assert payload["version"] == 3
    assert payload["settings"]["model_id"] == MODEL_WHAT_MALES_LEARN
    assert payload["model_lock"] == {
        "id": MODEL_WHAT_MALES_LEARN,
        "revision": 1,
        "renderer_profile": "infinite-comparison-v1",
    }


def test_model_manifest_exposes_user_and_model_owned_fields() -> None:
    manifest = model_manifest(get_model(MODEL_WHAT_MALES_LEARN))
    assert manifest["locked"] is True
    assert manifest["output"] == {"width": 1920, "height": 1080, "fps": 60}
    assert "card.image" in manifest["editable_fields"]
    assert "animation.outro" in manifest["locked_fields"]
