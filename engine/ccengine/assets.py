from __future__ import annotations

from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import hashlib
import mimetypes
import os
import shutil
import tempfile

from .models import Project

_REMOTE_PREFIXES = ("http://", "https://")
_MAX_REMOTE_BYTES = 64 * 1024 * 1024


def _safe_filename(value: str, fallback: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in value)
    return cleaned[:100] or fallback


def _extension_from_url(url: str, content_type: str = "") -> str:
    suffix = Path(urlparse(url).path).suffix.lower()
    if suffix and len(suffix) <= 8:
        return suffix
    guessed = mimetypes.guess_extension(content_type.split(";", 1)[0].strip()) if content_type else None
    return guessed or ".bin"


def remote_cache_directory() -> Path:
    if os.name == "nt":
        root = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    else:
        root = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
    path = root / "Cubical Create" / "remote-assets"
    path.mkdir(parents=True, exist_ok=True)
    return path


def materialize_remote_asset(url: str, cache_dir: Path | None = None) -> Path:
    cache = cache_dir or remote_cache_directory()
    cache.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha256(url.encode("utf-8")).hexdigest()
    metadata = cache / f"{key}.url"
    for candidate in cache.glob(f"{key}.*"):
        if candidate.name.endswith(".url") or candidate.name.endswith(".part"):
            continue
        if candidate.is_file() and candidate.stat().st_size > 0:
            return candidate

    request = Request(url, headers={"User-Agent": "Cubical-Create/1.0"})
    with urlopen(request, timeout=8) as response:
        content_type = response.headers.get("Content-Type", "")
        extension = _extension_from_url(url, content_type)
        destination = cache / f"{key}{extension}"
        temporary = destination.with_suffix(destination.suffix + ".part")
        total = 0
        with temporary.open("wb") as handle:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > _MAX_REMOTE_BYTES:
                    raise ValueError("Remote image is larger than 64 MB.")
                handle.write(chunk)
        os.replace(temporary, destination)
        metadata.write_text(url, encoding="utf-8")
        return destination


def resolve_asset_path(value: str, base: Path) -> str:
    raw = str(value or "").strip()
    if not raw or raw.lower().startswith(_REMOTE_PREFIXES):
        return raw
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = (base / path).resolve()
    return str(path)


def resolve_project_assets(project: Project, base: Path) -> Project:
    for card in project.cards:
        card.image = resolve_asset_path(card.image, base)
    settings = project.settings
    settings.soundtrack = resolve_asset_path(settings.soundtrack, base)
    for field in ("font_title", "font_description", "font_badge", "font_credits"):
        value = getattr(settings, field)
        # A family name has no path separator and should stay a family name.
        if value and ("/" in value or "\\" in value or Path(value).suffix):
            setattr(settings, field, resolve_asset_path(value, base))
    return project


def _copy_asset(value: str, assets_dir: Path, role: str) -> str:
    raw = str(value or "").strip()
    if not raw:
        return ""
    if raw.lower().startswith(_REMOTE_PREFIXES):
        source = materialize_remote_asset(raw)
    else:
        source = Path(raw).expanduser()
    if not source.is_file():
        raise FileNotFoundError(f"Missing {role}: {source}")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()[:12]
    name = _safe_filename(source.stem, role)
    destination = assets_dir / f"{role}-{name}-{digest}{source.suffix.lower()}"
    if not destination.exists():
        shutil.copy2(source, destination)
    return destination.name


def collect_project_assets(project: Project, target: str | Path) -> Project:
    """Return a portable copy whose local assets live beside the project file."""
    target_path = Path(target).expanduser().resolve()
    portable = Project.from_dict(project.to_dict())
    assets_dir = target_path.parent / f"{target_path.stem}_assets"
    assets_dir.mkdir(parents=True, exist_ok=True)

    for index, card in enumerate(portable.cards):
        if card.image:
            name = _copy_asset(card.image, assets_dir, f"card-{index + 1}")
            card.image = f"{assets_dir.name}/{name}"

    settings = portable.settings
    if settings.soundtrack:
        name = _copy_asset(settings.soundtrack, assets_dir, "soundtrack")
        settings.soundtrack = f"{assets_dir.name}/{name}"
    for field in ("font_title", "font_description", "font_badge", "font_credits"):
        value = getattr(settings, field)
        if value and ("/" in value or "\\" in value or Path(value).suffix):
            name = _copy_asset(value, assets_dir, field)
            setattr(settings, field, f"{assets_dir.name}/{name}")
    return portable
