from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
import hashlib
import json
import math
from pathlib import Path
import shutil
from typing import Any, Callable
import uuid
import zipfile

from PIL import Image

from .model_registry import get_model, normalize_model_id
from .models import Card, Project, ProjectSettings
from .reference_profiles import get_reference_profile
from .validation import normalize_project


MANIFEST_NAME = "megapack.json"
SUPPORTED_VERSION = 2
MAX_PACK_BYTES = 1_073_741_824
MAX_EXTRACTED_BYTES = 536_870_912
MAX_ENTRY_BYTES = 67_108_864
MAX_MANIFEST_BYTES = 4_194_304
MAX_ENTRIES = 1_000
MAX_CARDS = 500


@dataclass(frozen=True, slots=True)
class MegaPackImportResult:
    project: Project
    pack_name: str
    extracted_files: int
    warnings: tuple[str, ...]


def safe_entry_reference(reference: object) -> str:
    normalized = str(reference or "").strip().replace("\\", "/")
    if normalized.startswith("./"):
        normalized = normalized[2:]
    if not normalized:
        raise ValueError("MegaPack contains an empty file reference.")
    if normalized.startswith("/") or ":" in normalized:
        raise ValueError("MegaPack contains an unsafe file path.")
    if any(part in {"", ".", ".."} for part in normalized.split("/")):
        raise ValueError("MegaPack contains an unsafe file path.")
    return normalized


def _first_string(mapping: dict[str, Any] | None, *keys: str) -> str:
    if not isinstance(mapping, dict):
        return ""
    for key in keys:
        value = mapping.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _present_string(mapping: dict[str, Any] | None, *keys: str) -> str | None:
    if not isinstance(mapping, dict):
        return None
    for key in keys:
        if key in mapping and mapping[key] is not None:
            return str(mapping[key])
    return None


def _finite_float(value: object, fallback: float) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError, OverflowError):
        return fallback
    return result if math.isfinite(result) else fallback


def _centre_crop(
    source: Image.Image,
    size: tuple[int, int],
    *,
    focus_x: float = 0.5,
    focus_y: float = 0.5,
    zoom: float = 1.0,
) -> Image.Image:
    width, height = size
    rgba = source.convert("RGBA")
    destination_aspect = width / max(1, height)
    source_aspect = rgba.width / max(1, rgba.height)
    if source_aspect >= destination_aspect:
        base_height = float(rgba.height)
        base_width = base_height * destination_aspect
    else:
        base_width = float(rgba.width)
        base_height = base_width / max(0.0001, destination_aspect)
    zoom = max(1.0, min(3.0, zoom))
    crop_width = max(1.0, base_width / zoom)
    crop_height = max(1.0, base_height / zoom)
    focus_x = max(0.0, min(1.0, focus_x))
    focus_y = max(0.0, min(1.0, focus_y))
    left = max(0.0, min(rgba.width - crop_width, rgba.width * focus_x - crop_width / 2.0))
    top = max(0.0, min(rgba.height - crop_height, rgba.height * focus_y - crop_height / 2.0))
    cropped = rgba.crop((
        int(round(left)),
        int(round(top)),
        int(round(left + crop_width)),
        int(round(top + crop_height)),
    ))
    return cropped.resize((width, height), Image.Resampling.LANCZOS)


def _fallback_artwork(size: tuple[int, int]) -> Image.Image:
    width, height = size
    image = Image.new("RGBA", (1, height), (19, 141, 219, 255))
    pixels = image.load()
    transition = max(1, int(height * 0.72))
    for y in range(transition, height):
        amount = (y - transition) / max(1, height - transition - 1)
        colour = tuple(round(a + (b - a) * amount) for a, b in zip((19, 141, 219), (11, 116, 190)))
        pixels[0, y] = (*colour, 255)
    return image.resize((width, height), Image.Resampling.NEAREST)


def _compose_card_artwork(
    background: bytes | None,
    subject: bytes | None,
    size: tuple[int, int],
    *,
    focus_x: float,
    focus_y: float,
    zoom: float,
) -> Image.Image:
    if background is not None:
        with Image.open(BytesIO(background)) as source:
            if source.width <= 0 or source.height <= 0 or source.width * source.height > 64_000_000:
                raise ValueError("MegaPack image dimensions are not supported.")
            canvas = _centre_crop(source, size)
    else:
        canvas = _fallback_artwork(size)
    if subject is not None:
        with Image.open(BytesIO(subject)) as source:
            if source.width <= 0 or source.height <= 0 or source.width * source.height > 64_000_000:
                raise ValueError("MegaPack image dimensions are not supported.")
            foreground = _centre_crop(source, size, focus_x=focus_x, focus_y=focus_y, zoom=zoom)
        canvas.alpha_composite(foreground)
    return canvas.convert("RGB")


def _credit_lines(credits: dict[str, Any] | None) -> list[str]:
    if not isinstance(credits, dict):
        return []
    raw = credits.get("lines", credits.get("names"))
    if isinstance(raw, list):
        return [str(value).strip() for value in raw if str(value).strip()]
    if raw is None:
        return []
    return [line.strip() for line in str(raw).splitlines() if line.strip()]


def import_megapack(
    source: str | Path,
    assets: str | Path,
    *,
    progress: Callable[[int, int], None] | None = None,
    cancelled: Callable[[], bool] | None = None,
) -> MegaPackImportResult:
    pack_path = Path(source)
    assets_path = Path(assets)
    if not pack_path.is_file():
        raise FileNotFoundError("The selected MegaPack could not be opened.")
    if pack_path.stat().st_size > MAX_PACK_BYTES:
        raise ValueError("MegaPack is larger than the supported size limit.")
    if assets_path.exists() and any(assets_path.iterdir()):
        raise ValueError("MegaPack destination is not empty.")
    assets_path.mkdir(parents=True, exist_ok=True)

    try:
        with zipfile.ZipFile(pack_path) as archive:
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
            if not isinstance(cards_data, list):
                raise ValueError("MegaPack manifest has no cards array.")
            if not 1 <= len(cards_data) <= MAX_CARDS:
                raise ValueError(f"MegaPack must contain between 1 and {MAX_CARDS} cards.")

            indexed_entries: dict[str, zipfile.ZipInfo] = {}
            for entry in entries:
                if entry.is_dir():
                    continue
                safe_name = safe_entry_reference(entry.filename)
                if safe_name in indexed_entries:
                    raise ValueError(f"MegaPack contains duplicate file '{safe_name}'.")
                indexed_entries[safe_name] = entry

            extracted_bytes = 0
            extracted_files: dict[str, bytes] = {}

            def read_reference(reference: str) -> bytes:
                nonlocal extracted_bytes
                safe_name = safe_entry_reference(reference)
                if safe_name in extracted_files:
                    return extracted_files[safe_name]
                entry = indexed_entries.get(safe_name)
                if entry is None:
                    raise ValueError(f"MegaPack file '{safe_name}' was not found.")
                if entry.file_size > MAX_ENTRY_BYTES:
                    raise ValueError(f"MegaPack file '{safe_name}' is too large.")
                with archive.open(entry) as handle:
                    chunks: list[bytes] = []
                    total = 0
                    while True:
                        chunk = handle.read(1024 * 1024)
                        if not chunk:
                            break
                        total += len(chunk)
                        if total > MAX_ENTRY_BYTES:
                            raise ValueError(f"MegaPack file '{safe_name}' is too large.")
                        chunks.append(chunk)
                extracted_bytes += total
                if extracted_bytes > MAX_EXTRACTED_BYTES:
                    raise ValueError("MegaPack expands beyond the supported size limit.")
                data = b"".join(chunks)
                extracted_files[safe_name] = data
                return data

            model_id = normalize_model_id(_first_string(manifest, "model", "model_id"))
            model = get_model(model_id)
            profile = get_reference_profile(model.id)
            card_total = len(cards_data)
            if progress:
                progress(0, card_total)
            cards: list[Card] = []
            artwork_digests: dict[bytes, int] = {}
            warnings: list[str] = []
            missing_artwork = 0
            for index, item in enumerate(cards_data):
                if cancelled and cancelled():
                    raise RuntimeError("MegaPack import cancelled.")
                if not isinstance(item, dict):
                    raise ValueError(f"MegaPack card {index + 1} is not an object.")
                title = _first_string(item, "title", "name")
                description = _first_string(item, "description", "details")
                primary = _first_string(item, "badge_primary", "badgePrimary", "value")
                secondary = _first_string(item, "badge_secondary", "badgeSecondary", "label", "unit")
                value = " ".join(part for part in (primary, secondary) if part).strip()
                legacy_reference = _first_string(item, "image", "artwork")
                background_reference = _first_string(item, "background", "background_image", "backdrop")
                subject_reference = _first_string(item, "subject", "foreground", "subject_image") or legacy_reference
                background_data = read_reference(background_reference) if background_reference else None
                subject_data = read_reference(subject_reference) if subject_reference else None
                image_path = ""
                if background_data is not None or subject_data is not None:
                    description_height = (
                        profile.layout.body_height - profile.layout.description_top
                        if description else 0
                    )
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
                    except Exception as exc:
                        raise ValueError(f"MegaPack image for card {index + 1} is not a supported image.") from exc
                    image_file = assets_path / f"card-{index + 1:03d}.png"
                    artwork.save(image_file, format="PNG", optimize=True)
                    image_path = str(image_file.resolve())
                    digest = hashlib.sha256(artwork.tobytes()).digest()
                    if digest in artwork_digests:
                        warnings.append(
                            f"Card {index + 1} artwork appears to duplicate card {artwork_digests[digest]}."
                        )
                    else:
                        artwork_digests[digest] = index + 1
                    extrema = artwork.convert("L").getextrema()
                    if extrema[1] - extrema[0] <= 2:
                        warnings.append(f"Card {index + 1} may contain a blank image.")
                else:
                    missing_artwork += 1
                cards.append(Card(
                    title=title,
                    value=value,
                    description=description,
                    image=image_path,
                    id=uuid.uuid4().hex,
                ))
                if progress:
                    progress(index + 1, card_total)

            if missing_artwork == card_total:
                warnings.append("This MegaPack has no card images.")
            elif missing_artwork:
                noun = "card has" if missing_artwork == 1 else "cards have"
                warnings.append(f"{missing_artwork} {noun} no artwork.")

            settings = ProjectSettings(
                model_id=model.id,
                model_revision=model.revision,
                width=model.width,
                height=model.height,
                fps=model.fps,
            )
            # The remaining locked Males model owns a visible badge animation.
            # Older Relationships packs can incorrectly request hidden badges
            # while still supplying rank/value text.  Preserve that data and
            # render the model badge instead of silently discarding it.
            has_badge_data = any(card.value.strip() for card in cards)
            settings.show_badges = bool(manifest.get("show_badges", True)) or has_badge_data
            soundtrack_object = manifest.get("soundtrack")
            if isinstance(soundtrack_object, dict):
                soundtrack_reference = _first_string(soundtrack_object, "file", "path", "audio")
                settings.soundtrack_volume = max(
                    0.0,
                    min(1.0, _finite_float(soundtrack_object.get("volume"), 1.0)),
                )
                settings.soundtrack_loop = bool(soundtrack_object.get("loop", True))
            else:
                soundtrack_reference = str(soundtrack_object or "").strip()
            if soundtrack_reference:
                soundtrack_data = read_reference(soundtrack_reference)
                suffix = Path(safe_entry_reference(soundtrack_reference)).suffix.lower()
                if not suffix or len(suffix) > 9 or not suffix[1:].isalnum():
                    suffix = ".bin"
                soundtrack_path = assets_path / f"soundtrack{suffix}"
                soundtrack_path.write_bytes(soundtrack_data)
                settings.soundtrack = str(soundtrack_path.resolve())

            credits = manifest.get("credits") if isinstance(manifest.get("credits"), dict) else None
            lines = _credit_lines(credits)
            credits_heading = _present_string(credits, "heading", "title")
            if credits_heading is not None:
                settings.credits_heading = credits_heading
            if lines:
                settings.credits_project_name = lines[0]
                settings.credits_created_with_label = ""
                settings.credits_created_with_value = lines[1] if len(lines) > 1 else ""
                settings.credits_design_label = ""
                settings.credits_design_value = " · ".join(lines[2:])
            credits_footer = _present_string(credits, "footer")
            if credits_footer is not None:
                settings.credits_footer = credits_footer
            ending_heading = _present_string(credits, "ending_heading", "outro_heading")
            if ending_heading is not None:
                settings.end_credit_label = ending_heading
            ending_details = _present_string(credits, "ending_details", "outro_details")
            if ending_details is not None:
                settings.end_credit_value = ending_details
            settings.credits_enabled = bool(
                manifest.get("show_intro", True) or manifest.get("show_disclaimer", True)
            )

            intro = manifest.get("intro_video")
            if isinstance(intro, dict):
                intro_reference = _first_string(intro, "file", "path", "video")
                if intro_reference:
                    # Validate and preserve the referenced media even though the locked
                    # desktop reference starts at frame zero and cannot prepend it.
                    intro_data = read_reference(intro_reference)
                    suffix = Path(safe_entry_reference(intro_reference)).suffix.lower() or ".mp4"
                    (assets_path / f"intro{suffix}").write_bytes(intro_data)
                    warnings.append(
                        "The optional intro video was preserved, but the locked desktop reference cannot prepend it."
                    )
            if manifest.get("show_outro") is False:
                warnings.append("The locked desktop reference always keeps its measured outro.")

            pack_name = _first_string(manifest, "name", "title") or "CTS MegaPack"
            project = normalize_project(Project(name=pack_name, cards=cards, settings=settings))
            return MegaPackImportResult(
                project=project,
                pack_name=pack_name,
                extracted_files=len(extracted_files),
                warnings=tuple(warnings),
            )
    except Exception:
        shutil.rmtree(assets_path, ignore_errors=True)
        raise
