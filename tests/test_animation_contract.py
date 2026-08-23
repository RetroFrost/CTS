from __future__ import annotations

import pytest

from ccengine.models import Card, Project
from ccengine.renderer import FrameRenderer, badge_entry_affine, body_progress, post_badge_fall_affine
from ccengine.timing import card_start_times, reference_duration, total_duration


def test_males_uses_the_measured_integer_frame_contract() -> None:
    project = Project(cards=[Card(str(index), str(index)) for index in range(8)])
    assert card_start_times(project) == pytest.approx([
        0.0,
        2.0,
        4.0,
        6.0,
        528 / 60,
        742 / 60,
        956 / 60,
        1170 / 60,
    ])
    assert total_duration(project) == pytest.approx(1793 / 60)
    assert reference_duration(project) == pytest.approx(1793 / 60)


def test_body_motion_samples_are_unchanged() -> None:
    expected = {
        0.0: 0.0,
        0.1: 0.027910783211230757,
        0.5: 0.746,
        1.0: 0.971,
        1.34: 1.0,
    }
    for timestamp, value in expected.items():
        assert body_progress(timestamp) == pytest.approx(value, abs=1e-12)


def test_opening_badge_motion_is_disabled() -> None:
    def age(frame: int) -> float:
        return (frame - 35) * 2.9 / 85.0

    stationary = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    for frame in range(0, 201):
        assert badge_entry_affine(age(frame)) == stationary

def test_continuous_badge_fall_matches_contact_sheet_frames() -> None:
    def age(frame: int) -> float:
        return (frame - 650) * 2.25 / 103.0

    expected = {
        680: (1.0, 0.0, 0.0, 1.0, 0.0, -381.0),
        700: (1.0, 0.0, 0.0, 1.0, 0.0, -187.0),
        720: (1.0, 0.0, 0.0, 1.0, 0.0, -41.0),
        734: (1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
    }
    for frame, values in expected.items():
        assert post_badge_fall_affine(age(frame)) == pytest.approx(values, abs=1e-6)


def test_badge_value_layout_matches_new_reference() -> None:
    assert FrameRenderer._value_lines("7M YEARS AGO") == ["7M", "YEARS AGO"]
    assert FrameRenderer._value_lines("300K YEARS AGO") == ["300K", "YEARS AGO"]
    assert FrameRenderer._value_lines("7 YEARS OLD") == ["7", "YEARS OLD"]
    assert FrameRenderer._value_lines("8000 BC") == ["8000", "BC"]
