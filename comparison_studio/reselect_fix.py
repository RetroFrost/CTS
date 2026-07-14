from __future__ import annotations

from . import exporter as exporter_module
from .deselect_fix import DeselectFixedMainWindow
from .direct_transform import _clamp_box
from .interaction_runtime import RuntimeTransformRenderer
from .optional_hexagons import OptionalHexagonRenderer
from .studio_ui import StudioAssetCache


class ReselectAwareRenderer(OptionalHexagonRenderer):
    """Hit-test and edit objects at their transformed on-screen positions."""

    def _transformed_hit(
        self,
        cards,
        output_time: float,
        settings,
        normalized_x: float,
        normalized_y: float,
    ):
        # Newer transforms are visually on top of older ones, so search in reverse
        # insertion order when transformed boxes overlap.
        for (card_index, role), raw_box in reversed(list(self.transforms.items())):
            if not (0 <= card_index < len(cards)):
                continue
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue

            # Ignore transforms belonging to cards that are not currently visible.
            default_region = super().editor_region(
                cards,
                output_time,
                settings,
                card_index,
                role,
            )
            if default_region is None:
                continue

            x, y, width, height = _clamp_box(raw_box)
            if x <= normalized_x <= x + width and y <= normalized_y <= y + height:
                return card_index, role
        return None

    def hit_test(
        self,
        cards,
        output_time: float,
        settings,
        normalized_x: float,
        normalized_y: float,
    ):
        transformed = self._transformed_hit(
            cards,
            output_time,
            settings,
            normalized_x,
            normalized_y,
        )
        if transformed is not None:
            return transformed
        return super().hit_test(
            cards,
            output_time,
            settings,
            normalized_x,
            normalized_y,
        )

    def editor_region(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        role: str,
    ):
        # During frame composition TransformTimelineRenderer needs the original
        # model box as its crop source. UI editing and selection, however, must use
        # the object's current transformed destination box.
        if not self._applying_transforms:
            transformed = self.transforms.get((card_index, role))
            if transformed is not None:
                default_region = super().editor_region(
                    cards,
                    output_time,
                    settings,
                    card_index,
                    role,
                )
                if default_region is not None:
                    return _clamp_box(transformed)
        return super().editor_region(
            cards,
            output_time,
            settings,
            card_index,
            role,
        )


# Preview and MP4 export use the same transform-aware renderer.
exporter_module.TimelineRenderer = ReselectAwareRenderer


class ReselectFixedMainWindow(DeselectFixedMainWindow):
    """Final interaction window with transformed-position selection."""

    def _new_renderer(self) -> ReselectAwareRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return ReselectAwareRenderer(StudioAssetCache(), self.transform_overrides)

    def __init__(self) -> None:
        super().__init__()
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · moved text/images can be selected again · Esc or click outside deselects"
        )
        self.update_preview()
