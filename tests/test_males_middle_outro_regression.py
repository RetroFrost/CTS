from __future__ import annotations

from ccengine.exact_reference_frames import continuous_card_x
from ccengine.model_registry import MODEL_WHAT_MALES_LEARN
from ccengine.models import Card, Project, ProjectSettings
from ccengine.reference_profiles import get_reference_profile
from ccengine.renderer import FrameRenderer
from ccengine.scene import build_frame_scene
from ccengine.timing import card_start_frames, content_frame_count, frame_to_seconds, total_frame_count


def _canonical_project() -> Project:
    settings = ProjectSettings(model_id=MODEL_WHAT_MALES_LEARN)
    cards = [Card(f"Card {index + 1}", str(index + 1)) for index in range(57)]
    return Project(cards=cards, settings=settings)


def test_middle_cards_use_exact_measured_conveyor_coordinates() -> None:
    project = _canonical_project()
    renderer = FrameRenderer()
    renderer._active_settings = project.settings
    renderer._active_profile = get_reference_profile(MODEL_WHAT_MALES_LEARN)

    frame = 660
    starts = card_start_frames(project)
    positions = renderer._positions_for_frame(project, frame, starts)

    # Source contact-sheet measurement for the first visible card at f660.
    assert continuous_card_x(MODEL_WHAT_MALES_LEARN, frame, 0) == -368.0
    assert positions[0] == -368.0

    # Every middle card stays on the source strip's measured 476 px pitch.
    assert positions[1] == 108.0
    assert positions[2] == 584.0
    assert positions[3] == 1060.0
    assert positions[4] == 1536.0


def test_outro_handoffs_match_dense_contact_sheet_frames() -> None:
    project = _canonical_project()
    renderer = FrameRenderer()

    assert content_frame_count(project) == 11_858
    assert total_frame_count(project) == 12_267

    expected_segments = {
        11_857: "card_cycle",
        11_858: "end_wipe",
        11_868: "end_wipe",  # first non-zero black cover sample
        11_900: "end_wipe",
        11_901: "end_rise",  # end-card group first appears
        11_911: "end_rise",  # end-card group settles
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

    # The top-down cover remains invisible for ten frames, then follows the
    # measured dense-sheet offsets until it completely covers the left 1440 px.
    assert renderer._age_cover_y(9) == 0
    assert renderer._age_cover_y(10) == 28
    assert renderer._age_cover_y(26) == 1080

    # The end group enters from above at f11901 and is fully settled at f11911.
    assert renderer._age_end_group_top(42) is None
    assert renderer._age_end_group_top(43) == -210.0
    assert renderer._age_end_group_top(53) == 0.0

    # The final source-strip state stays frozen while the end sequence runs.
    final_x = continuous_card_x(MODEL_WHAT_MALES_LEARN, 11_841, 56)
    assert final_x == continuous_card_x(MODEL_WHAT_MALES_LEARN, 11_857, 56)
    assert final_x == continuous_card_x(MODEL_WHAT_MALES_LEARN, 11_901, 56)
    assert final_x == continuous_card_x(MODEL_WHAT_MALES_LEARN, 12_266, 56)
