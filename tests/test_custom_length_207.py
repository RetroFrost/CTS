from __future__ import annotations

import json

from ccengine.models import Project
from ccengine.timing import card_start_frames, total_duration


def project(card_count: int, seconds: float, auto: bool) -> Project:
    data = {
        "version": 3,
        "name": "Timing test",
        "cards": [
            {"id": str(i), "title": f"Card {i}", "value": str(i), "description": ""}
            for i in range(card_count)
        ],
        "settings": {
            "model_id": "what-males-learn-at-each-age",
            "fps": 60,
            "auto_length": auto,
            "custom_length_seconds": seconds,
        },
    }
    return Project.from_dict(json.loads(json.dumps(data)))


def test_custom_total_and_fixed_opening() -> None:
    p = project(12, 90.0, False)
    assert abs(total_duration(p) - 90.0) <= 1 / 60
    assert card_start_frames(p)[:4] == [0, 120, 240, 360]


def test_only_conveyor_cadence_changes() -> None:
    normal = project(12, 90.0, True)
    custom = project(12, 90.0, False)
    assert card_start_frames(normal)[:4] == card_start_frames(custom)[:4]
    assert card_start_frames(normal)[5] != card_start_frames(custom)[5]


if __name__ == "__main__":
    test_custom_total_and_fixed_opening()
    test_only_conveyor_cadence_changes()
    print("custom length tests passed")
