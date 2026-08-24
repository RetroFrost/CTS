from __future__ import annotations

import math

from .model_registry import get_model, normalize_model_id
from .models import Project

ENCODER_PRESETS: tuple[str, ...] = (
    "ultrafast", "superfast", "veryfast", "faster", "fast",
    "medium", "slow", "slower", "veryslow",
)
MAX_CARDS = 2000
MAX_WIDTH = 7680
MAX_HEIGHT = 4320
MAX_FPS = 120
MAX_TRANSFORM_SCALE = 8.0
MAX_TRANSFORM_OFFSET = 20000.0


def _finite(value: object, fallback: float) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError, OverflowError):
        return fallback
    return result if math.isfinite(result) else fallback


def _bounded_int(value: object, fallback: int, low: int, high: int) -> int:
    try:
        result = int(float(value))
    except (TypeError, ValueError, OverflowError):
        result = fallback
    return max(low, min(high, result))


def normalize_even_dimension(value: object, fallback: int, low: int, high: int) -> int:
    result = _bounded_int(value, fallback, low, high)
    if result % 2:
        result = result + 1 if result < high else result - 1
    return max(low, result)


def validate_encoder_preset(value: object, fallback: str = "faster") -> str:
    preset = str(value or "").strip().lower()
    return preset if preset in ENCODER_PRESETS else fallback


def normalize_project(project: Project, *, reject_excess_cards: bool = True) -> Project:
    """Normalise content while preserving the selected model exactly.

    Cubical Compare 1.0 models are not animation presets. They are locked
    reproduction contracts. Resolution, frame rate, cadence and model revision
    therefore come from the registry and cannot be stretched by project data.
    """
    if reject_excess_cards and len(project.cards) > MAX_CARDS:
        raise ValueError(f"Projects are limited to {MAX_CARDS} cards.")

    settings = project.settings
    settings.model_id = normalize_model_id(settings.model_id)
    model = get_model(settings.model_id)
    settings.model_revision = model.revision

    # Locked model-owned values. Legacy projects may contain different values,
    # but loading them into 1.0 must not mutate the canonical output mechanics.
    settings.width = model.width
    settings.height = model.height
    settings.fps = model.fps
    settings.auto_length = bool(settings.auto_length)

    settings.encoder_crf = _bounded_int(settings.encoder_crf, 18, 0, 51)
    settings.encoder_preset = validate_encoder_preset(settings.encoder_preset)
    settings.soundtrack_volume = max(0.0, min(1.0, _finite(settings.soundtrack_volume, 0.75)))
    settings.soundtrack_offset_seconds = max(0.0, _finite(settings.soundtrack_offset_seconds, 0.0))
    settings.soundtrack_fade_out_seconds = max(0.0, _finite(settings.soundtrack_fade_out_seconds, 0.75))
    settings.custom_length_seconds = max(0.0, _finite(settings.custom_length_seconds, 60.0))
    settings.image_fit_mode = "contain" if str(settings.image_fit_mode).lower() == "contain" else "cover"

    for card in project.cards:
        card.title = str(card.title or "")
        card.value = str(card.value or "")
        card.badge_header = str(card.badge_header or "")
        card.description = str(card.description or "")
        card.image = str(card.image or "")
        card.image_x = max(-MAX_TRANSFORM_OFFSET, min(MAX_TRANSFORM_OFFSET, _finite(card.image_x, 0.0)))
        card.image_y = max(-MAX_TRANSFORM_OFFSET, min(MAX_TRANSFORM_OFFSET, _finite(card.image_y, 0.0)))
        card.image_scale = max(0.05, min(MAX_TRANSFORM_SCALE, _finite(card.image_scale, 1.0)))
        card.image_rotation = _finite(card.image_rotation, 0.0) % 360.0
        card.image_crop_left = max(0.0, min(0.49, _finite(card.image_crop_left, 0.0)))
        card.image_crop_top = max(0.0, min(0.49, _finite(card.image_crop_top, 0.0)))
        card.image_crop_right = max(0.0, min(0.49, _finite(card.image_crop_right, 0.0)))
        card.image_crop_bottom = max(0.0, min(0.49, _finite(card.image_crop_bottom, 0.0)))
        card.image_layer = "front" if str(card.image_layer).lower() == "front" else "behind"
    return project
