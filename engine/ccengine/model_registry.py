from __future__ import annotations

from dataclasses import dataclass
from typing import Final


@dataclass(frozen=True, slots=True)
class ReferenceVideo:
    """Immutable identity of a canonical model reference video.

    Frame count and frame rate are the visual clock. Container/audio duration is
    deliberately not used because it may include encoder padding.
    """

    filename: str
    sha256: str
    width: int
    height: int
    fps_numerator: int
    fps_denominator: int
    frame_count: int
    first_content_frame: int

    @property
    def fps(self) -> float:
        return self.fps_numerator / self.fps_denominator

    @property
    def visual_duration_seconds(self) -> float:
        return self.frame_count / self.fps


@dataclass(frozen=True, slots=True)
class LockedModel:
    id: str
    revision: int
    display_name: str
    description: str
    renderer_profile: str
    reference: ReferenceVideo
    credits_panel: str
    editable_fields: tuple[str, ...]
    locked_fields: tuple[str, ...]

    @property
    def width(self) -> int:
        return self.reference.width

    @property
    def height(self) -> int:
        return self.reference.height

    @property
    def fps(self) -> int:
        if self.reference.fps_denominator != 1:
            raise ValueError(f"Model {self.id} does not use an integer frame rate")
        return self.reference.fps_numerator


MODEL_WHAT_MALES_LEARN: Final = "what-males-learn-at-each-age"
DEFAULT_MODEL_ID: Final = MODEL_WHAT_MALES_LEARN

_COMMON_EDITABLE = (
    "project.name",
    "cards.order",
    "card.title",
    "card.value",
    "card.description",
    "card.image",
    "card.image_transform",
    "soundtrack",
    "credits.text",
    "end_screen.text",
)

_COMMON_LOCKED = (
    "output.width",
    "output.height",
    "output.fps",
    "timeline.cadence",
    "timeline.scene_order",
    "animation.card_body",
    "animation.badge",
    "animation.text",
    "animation.shine",
    "animation.credits_panel",
    "animation.outro",
    "layout.card_geometry",
    "layout.badge_geometry",
    "layout.typography_metrics",
)

_MODELS: Final[dict[str, LockedModel]] = {
    MODEL_WHAT_MALES_LEARN: LockedModel(
        id=MODEL_WHAT_MALES_LEARN,
        revision=1,
        display_name="What Males Learn At Each Age",
        description=(
            "Exact 60 FPS Males reproduction profile measured from the complete "
            "Evolution Of Language reference. Cards and credits begin on frame zero."
        ),
        renderer_profile="infinite-comparison-v1",
        reference=ReferenceVideo(
            filename="Comparison： Evolution Of Language (400,000 BC - 2026).mp4",
            sha256="965d878c8343f820a66d34129c8a998de6a8039fed110a7f5fc1fd622ee355b2",
            width=1920,
            height=1080,
            fps_numerator=60,
            fps_denominator=1,
            frame_count=12267,
            first_content_frame=0,
        ),
        credits_panel="right-opening-panel",
        editable_fields=_COMMON_EDITABLE,
        locked_fields=_COMMON_LOCKED,
    ),
}

_ALIASES: Final[dict[str, str]] = {
    "": DEFAULT_MODEL_ID,
    "default": DEFAULT_MODEL_ID,
    "legacy": DEFAULT_MODEL_ID,
    "males-age": MODEL_WHAT_MALES_LEARN,
}


def normalize_model_id(value: object) -> str:
    candidate = str(value or "").strip().lower()
    candidate = _ALIASES.get(candidate, candidate)
    return candidate if candidate in _MODELS else DEFAULT_MODEL_ID


def get_model(value: object) -> LockedModel:
    return _MODELS[normalize_model_id(value)]


def list_models() -> tuple[LockedModel, ...]:
    return tuple(_MODELS.values())


def model_manifest(model: LockedModel) -> dict[str, object]:
    return {
        "id": model.id,
        "revision": model.revision,
        "display_name": model.display_name,
        "description": model.description,
        "renderer_profile": model.renderer_profile,
        "locked": True,
        "output": {
            "width": model.width,
            "height": model.height,
            "fps": model.fps,
        },
        "reference": {
            "filename": model.reference.filename,
            "sha256": model.reference.sha256,
            "frame_count": model.reference.frame_count,
            "visual_duration_seconds": model.reference.visual_duration_seconds,
            "first_content_frame": model.reference.first_content_frame,
        },
        "editable_fields": list(model.editable_fields),
        "locked_fields": list(model.locked_fields),
    }
