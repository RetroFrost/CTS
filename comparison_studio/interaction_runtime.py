from __future__ import annotations

from . import exporter as exporter_module
from .direct_transform import DirectTransformMainWindow, TransformTimelineRenderer
from .studio_ui import StudioAssetCache


class RuntimeTransformRenderer(TransformTimelineRenderer):
    ACTIVE_TRANSFORMS = {}

    def __init__(self, asset_cache=None, transforms=None) -> None:
        super().__init__(asset_cache or StudioAssetCache(), transforms if transforms is not None else self.ACTIVE_TRANSFORMS)


# Export workers create the renderer without window arguments, so use the same shared mapping.
exporter_module.TimelineRenderer = RuntimeTransformRenderer


class InteractionMainWindow(DirectTransformMainWindow):
    def __init__(self) -> None:
        super().__init__()
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        self.renderer = RuntimeTransformRenderer(StudioAssetCache(), self.transform_overrides)
        self.statusBar().showMessage(
            "Ready · Import file handles CSV/XLSX · left-click edits · right-click transforms"
        )
        self.update_preview()

    def _new_renderer(self) -> RuntimeTransformRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return RuntimeTransformRenderer(StudioAssetCache(), self.transform_overrides)
