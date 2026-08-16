from __future__ import annotations

from . import renderer as _renderer
from .watchdata_renderer import WatchDataFrameRenderer
from .watchdata_measured import MeasuredWatchDataFrameRenderer
from .watchdata_final import FinalWatchDataFrameRenderer
from .watchdata_release import ReleaseWatchDataFrameRenderer

_renderer.FrameRenderer = ReleaseWatchDataFrameRenderer
FrameRenderer = ReleaseWatchDataFrameRenderer

__all__ = [
    "FrameRenderer",
    "WatchDataFrameRenderer",
    "MeasuredWatchDataFrameRenderer",
    "FinalWatchDataFrameRenderer",
    "ReleaseWatchDataFrameRenderer",
]
