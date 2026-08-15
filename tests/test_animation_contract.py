from __future__ import annotations

import pytest

from ccengine.models import Card, Project
from ccengine.renderer import badge_entry_affine, body_progress, post_badge_fall_affine
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


def test_opening_badge_transform_matches_contact_sheet_frames() -> None:
    def age(frame: int) -> float:
        return (frame - 35) * 2.9 / 85.0

    expected = {
        40: (0.592169, -0.078765, -0.283855, 1.188568, -125.999331, -19.155194),
        60: (0.774691, -0.031915, -0.189815, 1.114362, -38.958629, -14.476950),
        80: (0.945988, -0.029255, -0.067901, 1.058511, -23.818558, -15.196217),
        100: (1.010802, -0.013298, -0.006173, 1.005319, -14.144799, -6.608747),
        120: (1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
    }
    for frame, values in expected.items():
        assert badge_entry_affine(age(frame)) == pytest.approx(values, abs=1e-6)

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
