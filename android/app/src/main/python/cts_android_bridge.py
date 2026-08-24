from __future__ import annotations

import gc
import json
from pathlib import Path
import shutil
import uuid
import zipfile

from ccengine.importers import load_spreadsheet
from ccengine.megapack import (
    MANIFEST_NAME,
    MAX_ENTRIES,
    MAX_ENTRY_BYTES,
    MAX_EXTRACTED_BYTES,
    MAX_MANIFEST_BYTES,
    MAX_PACK_BYTES,
    MAX_CARDS,
    SUPPORTED_VERSION,
    _compose_card_artwork,
    _finite_float,
    _first_string,
    _manifest_duration_seconds,
    safe_entry_reference,
)
from ccengine.model_registry import get_model, normalize_model_id
from ccengine.models import Project
from ccengine.reference_profiles import get_reference_profile
from ccengine.renderer import FrameRenderer
from ccengine.timing import total_duration, total_frame_count

_renderer = FrameRenderer()


def _project(text: str) -> Project:
    return Project.from_dict(json.loads(text))


def metadata(project_json: str) -> str:
    project = _project(project_json)
    return json.dumps({"frame_count": total_frame_count(project), "duration": total_duration(project), "fps": project.settings.fps})


def render_rgba(project_json: str, frame_index: int, width: int, height: int) -> bytes:
    project = _project(project_json)
    seconds = max(0, int(frame_index)) / max(1, project.settings.fps)
    image = _renderer.render(project, seconds, (max(2, int(width)), max(2, int(height))))
    return image.convert("RGBA").tobytes("raw", "RGBA")


def import_data(project_json: str, data_path: str) -> str:
    project = _project(project_json)
    cards = load_spreadsheet(data_path)
    if not cards:
        raise ValueError("No cards were found in the selected file.")
    project.cards = cards
    project.name = Path(data_path).stem.replace("_", " ").strip() or project.name
    return json.dumps(project.to_dict())


def _copy_archive_member(archive: zipfile.ZipFile, entry: zipfile.ZipInfo, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    with archive.open(entry) as source, destination.open("wb") as output:
        while True:
            chunk = source.read(1024 * 1024)
            if not chunk:
                break
            written += len(chunk)
            if written > MAX_ENTRY_BYTES:
                raise ValueError(f"MegaPack file '{entry.filename}' is too large.")
            output.write(chunk)


def import_pack(pack_path: str, asset_dir: str) -> str:
    """Memory-bounded Android MegaPack importer.

    Desktop import may cache referenced archive members for speed. On Android a
    large artwork pack can exceed the process heap when dozens of compressed
    images and their decoded RGBA surfaces are alive together. This bridge
    deliberately processes one card at a time and releases every source image
    before moving to the next card. The renderer package itself is untouched.
    """
    source = Path(pack_path)
    assets = Path(asset_dir)
    if not source.is_file():
        raise FileNotFoundError("The selected MegaPack could not be opened.")
    if source.stat().st_size > MAX_PACK_BYTES:
        raise ValueError("MegaPack is larger than the supported size limit.")
    if assets.exists() and any(assets.iterdir()):
        raise ValueError("MegaPack destination is not empty.")
    assets.mkdir(parents=True, exist_ok=True)

    try:
        with zipfile.ZipFile(source) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ENTRIES:
                raise ValueError("This MegaPack contains too many files.")
            try:
                manifest_entry = archive.getinfo(MANIFEST_NAME)
            except KeyError as exc:
                raise ValueError(f"MegaPack is missing {MANIFEST_NAME}.") from exc
            if manifest_entry.is_dir() or manifest_entry.file_size > MAX_MANIFEST_BYTES:
                raise ValueError("MegaPack manifest is too large.")
            manifest_bytes = archive.read(manifest_entry)
            if len(manifest_bytes) > MAX_MANIFEST_BYTES:
                raise ValueError("MegaPack manifest is too large.")
            try:
                manifest = json.loads(manifest_bytes.decode("utf-8-sig"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise ValueError("MegaPack manifest is not valid UTF-8 JSON.") from exc
            if not isinstance(manifest, dict):
                raise ValueError("MegaPack manifest must contain a JSON object.")
            version = manifest.get("version", SUPPORTED_VERSION)
            if isinstance(version, bool) or not isinstance(version, int) or not 1 <= version <= SUPPORTED_VERSION:
                raise ValueError(f"MegaPack version {version} is not supported.")
            cards_data = manifest.get("cards")
            if not isinstance(cards_data, list) or not 1 <= len(cards_data) <= MAX_CARDS:
                raise ValueError(f"MegaPack must contain between 1 and {MAX_CARDS} cards.")

            indexed: dict[str, zipfile.ZipInfo] = {}
            total_uncompressed = 0
            for entry in entries:
                if entry.is_dir():
                    continue
                safe = safe_entry_reference(entry.filename)
                if safe in indexed:
                    raise ValueError(f"MegaPack contains duplicate file '{safe}'.")
                if entry.file_size > MAX_ENTRY_BYTES:
                    raise ValueError(f"MegaPack file '{safe}' is too large.")
                total_uncompressed += entry.file_size
                if total_uncompressed > MAX_EXTRACTED_BYTES:
                    raise ValueError("MegaPack expands beyond the supported size limit.")
                indexed[safe] = entry

            model = get_model(normalize_model_id(_first_string(manifest, "model", "model_id")))
            profile = get_reference_profile(model.id)
            output_cards: list[dict[str, object]] = []

            for index, item in enumerate(cards_data):
                if not isinstance(item, dict):
                    raise ValueError(f"MegaPack card {index + 1} is not an object.")
                title = _first_string(item, "title", "name")
                description = _first_string(item, "description", "details")
                badge_header = _first_string(item, "badge_header", "badgeHeader", "header")
                primary = _first_string(item, "badge_primary", "badgePrimary", "value")
                secondary = _first_string(item, "badge_secondary", "badgeSecondary", "label", "unit")
                value = " ".join(part for part in (primary, secondary) if part).strip()
                legacy = _first_string(item, "image", "artwork")
                background_ref = _first_string(item, "background", "background_image", "backdrop")
                subject_ref = _first_string(item, "subject", "foreground", "subject_image") or legacy

                def member_bytes(reference: str) -> bytes | None:
                    if not reference:
                        return None
                    safe = safe_entry_reference(reference)
                    entry = indexed.get(safe)
                    if entry is None:
                        raise ValueError(f"MegaPack file '{safe}' was not found.")
                    data = archive.read(entry)
                    if len(data) > MAX_ENTRY_BYTES:
                        raise ValueError(f"MegaPack file '{safe}' is too large.")
                    return data

                background_data = member_bytes(background_ref)
                subject_data = member_bytes(subject_ref)
                image_path = ""
                if background_data is not None or subject_data is not None:
                    description_height = profile.layout.body_height - profile.layout.description_top if description else 0
                    image_height = max(
                        1,
                        profile.layout.body_height
                        - (profile.layout.title_height if title else 0)
                        - (profile.layout.divider_width if description else 0)
                        - description_height,
                    )
                    try:
                        artwork = _compose_card_artwork(
                            background_data,
                            subject_data,
                            (profile.layout.body_width, image_height),
                            focus_x=_finite_float(item.get("crop_focus_x"), 0.5),
                            focus_y=_finite_float(item.get("crop_focus_y"), 0.5),
                            zoom=_finite_float(item.get("crop_zoom"), 1.0),
                        )
                        image_file = assets / f"card-{index + 1:03d}.png"
                        artwork.save(image_file, format="PNG", optimize=False)
                        image_path = str(image_file.resolve())
                        artwork.close()
                    except Exception as exc:
                        raise ValueError(f"MegaPack image for card {index + 1} is not a supported image.") from exc
                    finally:
                        del background_data
                        del subject_data
                    if index % 4 == 3:
                        gc.collect()

                output_cards.append({
                    "id": uuid.uuid4().hex,
                    "title": title,
                    "badge_header": badge_header,
                    "value": value,
                    "description": description,
                    "image": image_path,
                    "image_x": 0.0,
                    "image_y": 0.0,
                    "image_scale": 1.0,
                    "image_rotation": 0.0,
                    "image_crop_left": 0.0,
                    "image_crop_top": 0.0,
                    "image_crop_right": 0.0,
                    "image_crop_bottom": 0.0,
                    "image_layer": "behind",
                })

            soundtrack = manifest.get("soundtrack")
            soundtrack_path = ""
            soundtrack_volume = 1.0
            soundtrack_loop = True
            if isinstance(soundtrack, dict):
                soundtrack_ref = _first_string(soundtrack, "file", "path", "audio")
                soundtrack_volume = max(0.0, min(1.0, _finite_float(soundtrack.get("volume"), 1.0)))
                soundtrack_loop = bool(soundtrack.get("loop", True))
            else:
                soundtrack_ref = str(soundtrack or "").strip()
            if soundtrack_ref:
                safe = safe_entry_reference(soundtrack_ref)
                entry = indexed.get(safe)
                if entry is None:
                    raise ValueError(f"MegaPack file '{safe}' was not found.")
                suffix = Path(safe).suffix.lower()
                if not suffix or len(suffix) > 9 or not suffix[1:].isalnum():
                    suffix = ".bin"
                destination = assets / f"soundtrack{suffix}"
                _copy_archive_member(archive, entry, destination)
                soundtrack_path = str(destination.resolve())

            has_badge_data = any(str(card["value"]).strip() for card in output_cards)
            requested_duration = _manifest_duration_seconds(manifest)
            settings = {
                "model_id": model.id,
                "model_revision": model.revision,
                "width": model.width,
                "height": model.height,
                "fps": model.fps,
                "auto_length": requested_duration <= 0.0,
                "custom_length_seconds": requested_duration if requested_duration > 0.0 else 60.0,
                "show_badges": bool(manifest.get("show_badges", True)) or has_badge_data,
                "credits_enabled": bool(manifest.get("credits_enabled", True)),
                "soundtrack": soundtrack_path,
                "soundtrack_volume": soundtrack_volume,
                "soundtrack_loop": soundtrack_loop,
                "encoder_preset": "faster",
                "encoder_crf": 18,
            }
            project = {
                "version": 3,
                "name": _first_string(manifest, "name", "title") or source.stem,
                "cards": output_cards,
                "settings": settings,
                "model_lock": {
                    "id": model.id,
                    "revision": model.revision,
                    "renderer_profile": model.renderer_profile,
                },
            }
            return json.dumps(project)
    except Exception:
        shutil.rmtree(assets, ignore_errors=True)
        raise
