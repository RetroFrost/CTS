from __future__ import annotations

# Import the measured base module first, then install the 2.0.4 visual-fidelity
# subclass as the package-wide FrameRenderer. Exporter, CLI and Android bridge
# all import ccengine before importing ccengine.renderer, so they receive the
# same implementation without duplicating the animation/timeline engine.
from . import renderer as _renderer
from .watchdata_renderer import WatchDataFrameRenderer

_renderer.FrameRenderer = WatchDataFrameRenderer
FrameRenderer = WatchDataFrameRenderer

__all__ = ["FrameRenderer", "WatchDataFrameRenderer"]
