from __future__ import annotations

from PIL import Image

from ccengine import renderer as base
from ccengine.watchdata_release import ReleaseWatchDataFrameRenderer


def _shine_alpha_bbox(progress: float):
    renderer = ReleaseWatchDataFrameRenderer()
    layer = Image.new("RGBA", base.BADGE_SOURCE_SIZE, (0, 0, 0, 0))
    age = base.SHINE_START + base.SHINE_SECONDS * progress
    renderer._add_badge_shine(layer, age)
    return layer.getchannel("A").getbbox()


def test_release_shine_physically_exits_badge_before_clock_ends() -> None:
    assert _shine_alpha_bbox(0.75) is not None
    assert _shine_alpha_bbox(0.97) is None
    assert _shine_alpha_bbox(1.00) is None
