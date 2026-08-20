from __future__ import annotations

from . import renderer as _renderer
from .watchdata_renderer import WatchDataFrameRenderer
from .watchdata_measured import MeasuredWatchDataFrameRenderer
from .watchdata_final import FinalWatchDataFrameRenderer
from .watchdata_release import ReleaseWatchDataFrameRenderer
from .watchdata_strict import StrictReferenceFrameRenderer

# The public renderer is the strict reference implementation. Measured frame
# and pixel paths are a hard contract: callers must never silently fall back
# to generic easing when exact source samples exist.
_renderer.FrameRenderer = StrictReferenceFrameRenderer
FrameRenderer = StrictReferenceFrameRenderer

__all__ = [
    "FrameRenderer",
    "WatchDataFrameRenderer",
    "MeasuredWatchDataFrameRenderer",
    "FinalWatchDataFrameRenderer",
    "ReleaseWatchDataFrameRenderer",
    "StrictReferenceFrameRenderer",
]
