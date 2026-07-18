from __future__ import annotations

from copy import deepcopy

from PIL import Image, ImageChops, ImageFilter

from . import exporter as exporter_module
from .data import REFERENCE_FADE_SECONDS
from .direct_transform import TransformBox, TransformKey
from .interaction_runtime import RuntimeTransformRenderer
from .renderer import BACKGROUND, _smoothstep
from .reselect_fix import ReselectAwareRenderer, ReselectFixedMainWindow
from .studio_ui import StudioAssetCache


class CardRelativeRenderer(ReselectAwareRenderer):
    """Render every image transform inside one card's own image frame.

    Image transform coordinates are normalized against the image frame belonging to
    the transform's card row.  They are not relative to the Program Monitor, the
    complete scrolling strip, or the whole card.  Text transforms remain card-local.
    """

    ACTIVE_TRANSFORMS: dict[TransformKey, TransformBox] = {}

    @staticmethod
    def _clamp_unit_box(box: TransformBox) -> TransformBox:
        """Clamp a normalized transform to one 0..1 owner rectangle."""
        x, y, width, height = (float(value) for value in box)
        width = max(0.025, min(1.0, width))
        height = max(0.025, min(1.0, height))
        x = max(0.0, min(1.0 - width, x))
        y = max(0.0, min(1.0 - height, y))
        return x, y, width, height

    def _owner_frame(self, model_id: str, role: str) -> TransformBox:
        """Return the role's frame inside one card.

        Images use the model's explicit image frame.  Other transformable roles keep
        the complete card as their owner rectangle to preserve the existing text-box
        transform behavior.
        """
        if role == "image":
            image_frame = self._field_box(model_id, "image")
            if image_frame is not None:
                return image_frame
        return 0.0, 0.0, 1.0, 1.0

    def _stored_to_card_box(
        self,
        model_id: str,
        role: str,
        stored_box: TransformBox,
    ) -> TransformBox:
        """Convert a role-owner transform into normalized card coordinates."""
        x, y, width, height = self._clamp_unit_box(stored_box)
        frame_x, frame_y, frame_width, frame_height = self._owner_frame(model_id, role)
        return (
            frame_x + x * frame_width,
            frame_y + y * frame_height,
            width * frame_width,
            height * frame_height,
        )

    def _card_to_stored_box(
        self,
        model_id: str,
        role: str,
        card_box: TransformBox,
    ) -> TransformBox:
        """Convert a card-local box into its role owner's local coordinates."""
        x, y, width, height = (float(value) for value in card_box)
        frame_x, frame_y, frame_width, frame_height = self._owner_frame(model_id, role)
        return self._clamp_unit_box(
            (
                (x - frame_x) / max(0.000001, frame_width),
                (y - frame_y) / max(0.000001, frame_height),
                width / max(0.000001, frame_width),
                height / max(0.000001, frame_height),
            )
        )

    def _card_box_to_global(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        card_box: TransformBox,
    ) -> TransformBox | None:
        placement = self._card_placement(cards, output_time, settings, card_index)
        if placement is None:
            return None
        card_x, card_width, alpha = placement
        x, y, width, height = card_box
        y_offset = (1.0 - alpha) * 0.018
        return card_x + x * card_width, y_offset + y, width * card_width, height

    def _global_box_to_card(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        global_box: TransformBox,
    ) -> TransformBox | None:
        placement = self._card_placement(cards, output_time, settings, card_index)
        if placement is None:
            return None
        card_x, card_width, alpha = placement
        x, y, width, height = (float(value) for value in global_box)
        y_offset = (1.0 - alpha) * 0.018
        return (
            (x - card_x) / max(0.000001, card_width),
            y - y_offset,
            width / max(0.000001, card_width),
            height,
        )

    def transform_to_global(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        role: str,
        stored_box: TransformBox,
    ) -> TransformBox | None:
        """Map one stored transform through its owner frame and owning card."""
        card_box = self._stored_to_card_box(settings.model_id, role, stored_box)
        return self._card_box_to_global(
            cards,
            output_time,
            settings,
            card_index,
            card_box,
        )

    def global_to_transform(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        role: str,
        global_box: TransformBox,
    ) -> TransformBox | None:
        """Store a dragged monitor box relative to its exact owner frame."""
        card_box = self._global_box_to_card(
            cards,
            output_time,
            settings,
            card_index,
            global_box,
        )
        if card_box is None:
            return None
        return self._card_to_stored_box(settings.model_id, role, card_box)

    def image_frame_for_card(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
    ) -> TransformBox | None:
        """Return the live monitor rectangle of one card's own image frame."""
        return self.transform_to_global(
            cards,
            output_time,
            settings,
            card_index,
            "image",
            (0.0, 0.0, 1.0, 1.0),
        )

    def _render_transformed_card_group(
        self,
        card,
        active: list[tuple[str, TransformBox]],
        card_width: int,
        card_height: int,
        badge_opacity: float,
        alpha: float,
        model_id: str,
    ) -> Image.Image:
        """Build one complete card group, including its independently framed image."""
        pristine = super()._render_card(
            card,
            card_width,
            card_height,
            badge_opacity,
            1.0,
            model_id,
        ).convert("RGBA")

        blank_card = deepcopy(card)
        for role, _target in active:
            self._blank_role(blank_card, role)
        blank = super()._render_card(
            blank_card,
            card_width,
            card_height,
            badge_opacity,
            1.0,
            model_id,
        ).convert("RGBA")
        group = blank.copy()

        for role, stored_target in active:
            source_region = self._field_box(model_id, role)
            if source_region is None:
                continue

            source_box = self._local_pixel_box(source_region, pristine.size)
            foreground = pristine.crop(source_box)
            background = blank.crop(source_box)
            difference = ImageChops.difference(foreground, background).convert("L")
            mask = difference.point(lambda value: 255 if value > 8 else 0).filter(
                ImageFilter.GaussianBlur(0.55)
            )
            if mask.getbbox() is None:
                continue
            foreground.putalpha(mask)

            target_x, target_y, target_width, target_height = self._stored_to_card_box(
                model_id,
                role,
                stored_target,
            )
            target_size = (
                max(1, round(target_width * card_width)),
                max(1, round(target_height * card_height)),
            )
            foreground = foreground.resize(target_size, Image.Resampling.LANCZOS)
            self._composite_clipped(
                group,
                foreground,
                round(target_x * card_width),
                round(target_y * card_height),
            )

        # The reveal animation is applied once to the entire owning card.  The image
        # frame and its image therefore cannot appear or move independently.
        if alpha < 0.999:
            channel = group.getchannel("A").point(lambda value: round(value * alpha))
            group.putalpha(channel)
        return group

    def render(self, cards, output_time: float, settings, size=None):
        self._studio_settings = settings
        width, height = size or (settings.width, settings.height)
        frame = Image.new("RGBA", (width, height), BACKGROUND + (255,))
        if not cards:
            return frame.convert("RGB")

        model_time = settings.model_time(output_time, len(cards))
        automatic_duration = settings.auto_duration(len(cards))
        if model_time >= automatic_duration:
            return frame.convert("RGB")

        visible_cards = settings.effective_visible_cards()
        card_width_float = width / visible_cards
        card_width = max(1, round(card_width_float))
        placements = self._placements(
            len(cards), model_time, visible_cards, width, settings.hexagons_bounce
        )

        for card_index, card_x, alpha, badge_opacity in placements:
            active = [
                (role, self._clamp_unit_box(box))
                for (index, role), box in self.transforms.items()
                if index == card_index
                and (self._show_hexagons or role not in {"badge_primary", "badge_secondary"})
                and self._field_box(settings.model_id, role) is not None
            ]
            y_offset = round((1.0 - alpha) * height * 0.018)

            if active:
                card_group = self._render_transformed_card_group(
                    cards[card_index],
                    active,
                    card_width,
                    height,
                    badge_opacity,
                    alpha,
                    settings.model_id,
                )
                self._composite_clipped(
                    frame,
                    card_group,
                    round(card_x),
                    y_offset,
                )
            else:
                card_layer = super()._render_card(
                    cards[card_index],
                    card_width,
                    height,
                    badge_opacity,
                    alpha,
                    settings.model_id,
                )
                self._composite_clipped(frame, card_layer, round(card_x), y_offset)

        fade_start = automatic_duration - REFERENCE_FADE_SECONDS
        result = frame.convert("RGB")
        if model_time > fade_start:
            fade = _smoothstep((model_time - fade_start) / REFERENCE_FADE_SECONDS)
            result = Image.blend(result, Image.new("RGB", result.size, BACKGROUND), fade)
        return result

    def _transformed_hit(
        self,
        cards,
        output_time: float,
        settings,
        normalized_x: float,
        normalized_y: float,
    ):
        self._studio_settings = settings
        for (card_index, role), stored_box in reversed(list(self.transforms.items())):
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue
            global_box = self.transform_to_global(
                cards,
                output_time,
                settings,
                card_index,
                role,
                stored_box,
            )
            if global_box is None:
                continue
            x, y, width, height = global_box
            if x <= normalized_x <= x + width and y <= normalized_y <= y + height:
                return card_index, role
        return None

    def editor_region(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        role: str,
    ):
        self._studio_settings = settings
        stored_box = self.transforms.get((card_index, role))
        if stored_box is not None:
            global_box = self.transform_to_global(
                cards,
                output_time,
                settings,
                card_index,
                role,
                stored_box,
            )
            return self._clip_global_box(global_box) if global_box is not None else None
        return super().editor_region(
            cards,
            output_time,
            settings,
            card_index,
            role,
        )


# Preview and MP4 export resolve the same per-card, per-image-frame renderer.
exporter_module.TimelineRenderer = CardRelativeRenderer


class CardRelativeMainWindow(ReselectFixedMainWindow):
    """CTS window where every image transform belongs to one explicit image frame."""

    transform_space = "per_card_image_frame_v3"

    def _new_renderer(self) -> CardRelativeRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        ReselectAwareRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        CardRelativeRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return CardRelativeRenderer(StudioAssetCache(), self.transform_overrides)

    def _transform_changed(self, card_index: int, role: str, box: object) -> None:
        if not (isinstance(box, tuple) and len(box) == 4):
            return
        settings = self.project_settings()
        cards = self.cards()
        stored = self.renderer.global_to_transform(
            cards,
            self.position_seconds,
            settings,
            card_index,
            role,
            tuple(float(value) for value in box),
        )
        if stored is None:
            self.statusBar().showMessage(
                "That card is not visible, so the transform was not changed.", 3500
            )
            return
        self.transform_overrides[(card_index, role)] = stored
        self.renderer = self._new_renderer()
        self.update_preview()
        current = self.renderer.editor_region(
            cards,
            self.position_seconds,
            settings,
            card_index,
            role,
        )
        if current is not None:
            self.preview.begin_transform(card_index, role, current)

    def _normalize_loaded_transforms(self, transforms, transform_space: str):
        settings = self.project_settings()
        converter = CardRelativeRenderer(StudioAssetCache(), {})
        converter._studio_settings = settings

        if transform_space == self.transform_space:
            return {
                key: converter._clamp_unit_box(box)
                for key, box in transforms.items()
            }

        # The two previous card-relative formats stored image boxes against the whole
        # card.  Convert those boxes into the explicit image frame of their own card.
        if transform_space in {"card_relative_v1", "card_relative_card_bounds_v2"}:
            card_local = transforms
        else:
            card_local = super()._normalize_loaded_transforms(
                transforms,
                transform_space,
            )

        return {
            (card_index, role): converter._card_to_stored_box(
                settings.model_id,
                role,
                box,
            )
            for (card_index, role), box in card_local.items()
        }

    def __init__(self) -> None:
        super().__init__()
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · every card has its own image frame and every image follows only that frame"
        )
        self.update_preview()
