from __future__ import annotations

from PIL import Image

from . import renderer as _base
from .models import Project
from .watchdata_strict import StrictReferenceFrameRenderer


_STATIONARY_AFFINE = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


class BadgeExactReferenceFrameRenderer(StrictReferenceFrameRenderer):
    """Final badge renderer with entrance motion completely disabled.

    Opening badge shells are present and stationary as soon as their cards
    appear, bypassing the invented sticker pop. Later conveyor badges retain
    their measured vertical fall from the reference. Reference size hierarchy,
    badge text timing and shine remain unchanged, as do all card animations.
    """

    def _opening_entry_affine(
        self,
        local_frame: int,
        age: float,
    ) -> tuple[float, float, float, float, float, float]:
        del local_frame, age
        return _STATIONARY_AFFINE

    def _draw_badge(
        self,
        canvas: Image.Image,
        project: Project,
        index: int,
        card_x: float,
        global_frame: int,
        starts: list[int],
    ) -> None:
        if not project.settings.show_badges or index >= len(starts):
            return
        card = project.cards[index]
        if not card.value:
            return

        local_frame = int(global_frame) - starts[index]
        if local_frame < 0:
            return

        if index < 4:
            source = self._badge_source(
                card,
                self._opening_source_age(local_frame),
                # Keep the badge shine clock, but remove the old directional
                # streak which visually reintroduced the disabled pop.
                sticker_entry=False,
            )
            scale = self._reference_opening_scale(
                index,
                int(global_frame),
                local_frame,
                starts,
            )
            base_affine = _STATIONARY_AFFINE
        else:
            measured_age = _base.age_later_badge_age(local_frame)
            if measured_age < 0.0:
                return
            source = self._badge_source(
                card,
                self._later_source_age(local_frame),
                sticker_entry=False,
            )
            scale = self._reference_later_scale(
                index,
                int(global_frame),
                starts,
            )
            base_affine = _base.post_badge_fall_affine(measured_age)

        affine = self._compose_source_scale(
            base_affine,
            scale,
            center=(240.0, 240.0),
        )
        self._warp_badge(canvas, source, card_x, affine)
