from __future__ import annotations

from . import renderer as _renderer
from .watchdata_renderer import WatchDataFrameRenderer
from .watchdata_measured import MeasuredWatchDataFrameRenderer
from .watchdata_final import FinalWatchDataFrameRenderer

_renderer.FrameRenderer = FinalWatchDataFrameRenderer
FrameRenderer = FinalWatchDataFrameRenderer

__all__ = [
    "FrameRenderer",
    "WatchDataFrameRenderer",
    "MeasuredWatchDataFrameRenderer",
    "FinalWatchDataFrameRenderer",
]
