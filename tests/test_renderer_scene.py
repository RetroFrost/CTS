from __future__ import annotations

from ccengine.model_registry import MODEL_TYPES_OF_RELATIONSHIPS
from ccengine.models import Card, Project, ProjectSettings
from ccengine.scene import build_frame_scene
from ccengine.timing import card_start_frames, content_frame_count, locate_frame, seconds_to_frame


def test_scene_is_the_only_timeline_sample_consumed_by_renderer() -> None:
    project = Project(
        cards=[Card(f"Card {index}", str(index)) for index in range(8)],
        settings=ProjectSettings(model_id=MODEL_TYPES_OF_RELATIONSHIPS),
    )
    seconds = 17.25
    scene = build_frame_scene(project, seconds)
    expected_frame = seconds_to_frame(project, seconds)
    segment, local, start = locate_frame(project, expected_frame)

    assert scene.global_frame == expected_frame
    assert scene.segment == segment
    assert scene.segment_start_frame == start
    assert scene.segment_progress == local / max(1, segment.frame_count - 1)
    assert scene.card_starts == tuple(card_start_frames(project))
    assert scene.content_end_frame == content_frame_count(project)
    assert scene.relationships


def test_scene_clamps_invalid_time_before_sampling() -> None:
    project = Project(cards=[Card("One", "1")])
    assert build_frame_scene(project, -10).global_frame == 0
    assert build_frame_scene(project, float("nan")).global_frame == 0
    assert build_frame_scene(project, float("inf")).global_frame == 0
