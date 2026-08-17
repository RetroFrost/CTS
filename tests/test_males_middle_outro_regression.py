from __future__ import annotations

from ccengine import canary_reference as ref
from ccengine.exact_reference_frames import continuous_card_x
from ccengine.model_registry import MODEL_WHAT_MALES_LEARN
from ccengine.models import Card, Project, ProjectSettings
from ccengine.scene import build_frame_scene
from ccengine.timing import content_frame_count, frame_to_seconds, total_frame_count


def _canonical_project() -> Project:
    settings = ProjectSettings(model_id=MODEL_WHAT_MALES_LEARN)
    cards = [Card(f"Card {index + 1}", str(index + 1)) for index in range(57)]
    return Project(cards=cards, settings=settings)


def test_middle_cards_use_canary_measured_conveyor_coordinates() -> None:
    frame = 660
    assert continuous_card_x(MODEL_WHAT_MALES_LEARN, frame, 0) == -371.0
    assert [continuous_card_x(MODEL_WHAT_MALES_LEARN, frame, i) for i in range(5)] == [
        -371.0, 106.0, 583.0, 1060.0, 1537.0,
    ]
    assert ref.SLOT_PITCH == 477


def test_outro_handoffs_keep_exact_source_frame_addresses() -> None:
    project = _canonical_project()
    assert content_frame_count(project) == 11_858
    assert total_frame_count(project) == 12_267
    expected_segments = {
        11_857: "card_cycle",
        11_858: "end_wipe",
        11_868: "end_wipe",
        11_900: "end_wipe",
        11_901: "end_rise",
        11_911: "end_rise",
        11_912: "end_hold",
        12_179: "end_hold",
        12_180: "fade",
        12_258: "fade",
        12_259: "black_tail",
        12_266: "black_tail",
    }
    for frame, kind in expected_segments.items():
        scene = build_frame_scene(project, frame_to_seconds(project, frame))
        assert scene.segment is not None
        assert scene.segment.kind == kind


def test_canary_last_strip_position_is_source_measured() -> None:
    assert ref.continuous_body_x(11_841, 56) == 1450
    assert ref.continuous_body_x(11_857, 56) == 1446
    assert ref.continuous_body_x(11_858, 56) == 1446
