from __future__ import annotations

from argparse import Namespace
import json
from pathlib import Path

from ccengine.model_registry import (
    MODEL_TYPES_OF_RELATIONSHIPS,
    MODEL_WHAT_MALES_LEARN,
)
from engine_cli import (
    command_list_models,
    command_new,
    command_validate,
    read_ccx,
)


def test_list_models_exposes_exactly_two_locked_official_models(capsys) -> None:
    assert command_list_models(Namespace()) == 0
    payload = json.loads(capsys.readouterr().out)
    assert [item["id"] for item in payload] == [
        MODEL_TYPES_OF_RELATIONSHIPS,
        MODEL_WHAT_MALES_LEARN,
    ]
    for item in payload:
        assert item["locked"] is True
        assert item["output"] == {"width": 1920, "height": 1080, "fps": 60}
        assert item["reference"]["sha256"]
        assert item["reference"]["frame_count"] > 0


def test_new_project_locks_the_requested_model(tmp_path: Path) -> None:
    output = tmp_path / "relationships.ccx"
    assert command_new(Namespace(
        output=str(output),
        model=MODEL_TYPES_OF_RELATIONSHIPS,
    )) == 0
    project = read_ccx(output)
    assert project.settings.model_id == MODEL_TYPES_OF_RELATIONSHIPS
    assert project.settings.model_revision == 1
    assert (project.settings.width, project.settings.height, project.settings.fps) == (1920, 1080, 60)


def test_validate_reports_model_and_integer_frame_count(tmp_path: Path, capsys) -> None:
    output = tmp_path / "ages.ccx"
    command_new(Namespace(output=str(output), model=MODEL_WHAT_MALES_LEARN))
    capsys.readouterr()

    assert command_validate(Namespace(input=str(output))) == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["model"]["id"] == MODEL_WHAT_MALES_LEARN
    assert payload["model"]["locked"] is True
    assert payload["resolution"] == [1920, 1080]
    assert payload["fps"] == 60
    assert isinstance(payload["frame_count"], int)
    assert payload["frame_count"] > 0
