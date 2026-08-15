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


def test_opening_badge_transform_is_unchanged() -> None:
    expected = {
        0.0: (0.32, 0.04, -0.43, 1.05, -230.0, -220.0),
        0.4: (0.36, 0.036, -0.43, 1.05, -154.0, -107.0),
        0.9: (1.2222, -0.0181, -0.2694, 1.6357, -141.9, -203.87),
        1.5: (1.2088, -0.0758, -0.0337, 1.2452, -56.12, -83.62),
        2.3: (1.0808, 0.0209, 0.0, 1.0698, -25.8, -16.16),
        2.9: (1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
    }
    for timestamp, values in expected.items():
        assert badge_entry_affine(timestamp) == pytest.approx(values, abs=1e-10)


def test_continuous_badge_fall_is_unchanged() -> None:
    expected = {
        0.0: (1.12, 0.0, 0.0, 1.12, -28.8, -443.76),
        0.55: (1.112, 0.0, 0.0, 1.112, -26.88, -314.176),
        1.05: (1.09, 0.0, 0.0, 1.09, -21.6, -122.82),
        1.42: (1.058, 0.0, 0.0, 1.058, -13.92, 4.516),
        2.25: (1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
    }
    for timestamp, values in expected.items():
        assert post_badge_fall_affine(timestamp) == pytest.approx(values, abs=1e-10)
