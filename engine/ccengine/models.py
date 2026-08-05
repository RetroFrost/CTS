from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any
import json
import uuid
import math


def _safe_float(value: Any, fallback: float) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError, OverflowError):
        return fallback
    return result if math.isfinite(result) else fallback


@dataclass(slots=True)
class Card:
    title: str = ""
    value: str = ""
    description: str = ""
    image: str = ""
    id: str = field(default_factory=lambda: uuid.uuid4().hex)

    # Per-card free transform. Coordinates are reference-card pixels (480x1080),
    # scale is multiplicative, crops are normalized fractions, and the layer
    # decides whether artwork sits behind or in front of card text/bands.
    image_x: float = 0.0
    image_y: float = 0.0
    image_scale: float = 1.0
    image_rotation: float = 0.0
    image_crop_left: float = 0.0
    image_crop_top: float = 0.0
    image_crop_right: float = 0.0
    image_crop_bottom: float = 0.0
    image_layer: str = "behind"

    @classmethod
    def from_mapping(cls, row: dict[str, Any]) -> "Card":
        return cls(
            title=str(row.get("title", "") or "").strip(),
            value=str(row.get("value", "") or "").strip(),
            description=str(row.get("description", "") or "").strip(),
            image=str(row.get("image", "") or "").strip(),
            id=str(row.get("id", "") or "").strip() or uuid.uuid4().hex,
            image_x=_safe_float(row.get("image_x", 0.0), 0.0),
            image_y=_safe_float(row.get("image_y", 0.0), 0.0),
            image_scale=_safe_float(row.get("image_scale", 1.0), 1.0),
            image_rotation=_safe_float(row.get("image_rotation", 0.0), 0.0),
            image_crop_left=max(0.0, min(0.49, _safe_float(row.get("image_crop_left", 0.0), 0.0))),
            image_crop_top=max(0.0, min(0.49, _safe_float(row.get("image_crop_top", 0.0), 0.0))),
            image_crop_right=max(0.0, min(0.49, _safe_float(row.get("image_crop_right", 0.0), 0.0))),
            image_crop_bottom=max(0.0, min(0.49, _safe_float(row.get("image_crop_bottom", 0.0), 0.0))),
            image_layer="front" if str(row.get("image_layer", "behind")).lower() == "front" else "behind",
        )


@dataclass(slots=True)
class ProjectSettings:
    width: int = 1920
    height: int = 1080
    fps: int = 60

    # Opening credits. Every visible string is project-editable.
    credits_enabled: bool = True
    credits_top_text: str = "Values are estimates and may vary."
    credits_heading: str = "Credits"
    credits_project_name: str = "Cubical Compare"
    credits_created_with_label: str = "Created with"
    credits_created_with_value: str = "Cubical Compare"
    credits_design_label: str = "Design & Rendering"
    credits_design_value: str = "Cubical"
    credits_footer: str = "CREDITS ARE OPTIONAL"

    # End-screen labels are editable too.
    end_best_label: str = "BEST VIDEO FOR YOU"
    end_newest_label: str = "NEWEST VIDEO"
    end_credit_label: str = "Video Made By"
    end_credit_value: str = "Cubical Compare"

    auto_length: bool = True
    custom_length_seconds: float = 60.0

    # Soundtrack controls.
    soundtrack: str = ""
    soundtrack_volume: float = 0.75
    soundtrack_loop: bool = True
    soundtrack_offset_seconds: float = 0.0
    soundtrack_fade_out_seconds: float = 0.75

    # Export controls. "faster" keeps the requested resolution/FPS/CRF while
    # reducing encoder work compared with the former medium preset.
    encoder_preset: str = "faster"
    encoder_crf: int = 18

    # Optional custom installed font files. Blank means Cubical Compare chooses
    # a condensed system font close to the reference video.
    font_title: str = ""
    font_description: str = ""
    font_badge: str = ""
    font_credits: str = ""

    # How ordinary, non-sheet images fit the artwork area.
    image_fit_mode: str = "cover"  # cover | contain

    # Compatibility aliases for projects saved by CTS Alpha 1–13.
    credits_title: str = ""
    credits_subtitle: str = ""

    def migrate_legacy(self) -> None:
        if self.credits_title and self.credits_project_name == "Cubical Compare":
            self.credits_project_name = self.credits_title
        if self.credits_subtitle and self.credits_top_text == "Values are estimates and may vary.":
            self.credits_top_text = self.credits_subtitle
        if self.credits_title and self.end_credit_value == "Cubical Compare":
            self.end_credit_value = self.credits_title


@dataclass(slots=True)
class Project:
    name: str = ""
    cards: list[Card] = field(default_factory=list)
    settings: ProjectSettings = field(default_factory=ProjectSettings)
    path: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": 2,
            "name": self.name,
            "cards": [asdict(card) for card in self.cards],
            "settings": asdict(self.settings),
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Project":
        settings_data = data.get("settings", {})
        settings = ProjectSettings(**{
            key: value
            for key, value in settings_data.items()
            if key in ProjectSettings.__dataclass_fields__
        })
        settings.migrate_legacy()
        cards = [Card.from_mapping(item) for item in data.get("cards", [])]
        project = cls(
            name=str(data.get("name", "")),
            cards=cards,
            settings=settings,
        )
        from .validation import normalize_project
        return normalize_project(project)

    def save(self, path: str | Path) -> None:
        output = Path(path)
        from .validation import normalize_project
        normalize_project(self)
        output.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")
        self.path = str(output)

    @classmethod
    def load(cls, path: str | Path) -> "Project":
        source = Path(path)
        project = cls.from_dict(json.loads(source.read_text(encoding="utf-8")))
        from .assets import resolve_project_assets
        resolve_project_assets(project, source.resolve().parent)
        project.path = str(source)
        return project
