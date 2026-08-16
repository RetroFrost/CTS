from __future__ import annotations

# Import the measured timeline renderer first, then install the final 2.0.4
# WatchData visual-fidelity layer package-wide. Exporter, CLI and Android all
# import ccengine before importing ccengine.renderer, so every path receives
# the same implementation without duplicating the conveyor/timeline engine.
from . import renderer as _renderer
from .watchdata_renderer import WatchDataFrameRenderer
from .watchdata_measured import MeasuredWatchDataFrameRenderer

_renderer.FrameRenderer = MeasuredWatchDataFrameRenderer
FrameRenderer = MeasuredWatchDataFrameRenderer

__all__ = [
    "FrameRenderer",
    "WatchDataFrameRenderer",
    "MeasuredWatchDataFrameRenderer",
]
