from __future__ import annotations

from io import BytesIO
import json
from pathlib import Path
import zipfile

from PIL import Image, ImageDraw
import pytest

import engine_cli
from ccengine.megapack import import_megapack, safe_entry_reference


def _png(image: Image.Image) -> bytes:
    output = BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


def _make_pack(path: Path) -> None:
    background = Image.new("RGB", (120, 120), (20, 150, 40))
    subject = Image.new("RGBA", (120, 120), (0, 0, 0, 0))
    ImageDraw.Draw(subject).ellipse((40, 40, 80, 80), fill=(230, 20, 20, 255))
    manifest = {
        "version": 2,
        "name": "Language MegaPack",
        "model": "males",
        "model_mode": "exact_reference",
        "show_badges": False,
        "cards": [{
            "badge_primary": "10",
            "badge_secondary": "SECONDS OLD",
            "title": "Breathing",
            "description": "A baby's first breath.",
            "background": "backgrounds/001.png",
            "subject": "subjects/001.png",
            "crop_focus_x": 0.5,
            "crop_focus_y": 0.5,
            "crop_zoom": 1.0,
        }],
        "credits": {
            "heading": "Credits",
            "lines": ["Research · Ethan", "Artwork · Cubical Network"],
            "footer": "SOURCES IN DESCRIPTION",
            "ending_heading": "Video Made By",
            "ending_details": "Cubical Network",
        },
        "soundtrack": {"file": "audio/theme.mp3", "volume": 0.8, "loop": False},
    }
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("megapack.json", json.dumps(manifest))
        archive.writestr("backgrounds/001.png", _png(background))
        archive.writestr("subjects/001.png", _png(subject))
        archive.writestr("audio/theme.mp3", b"ID3" + b"\0" * 128)


def test_safe_megapack_paths_match_android_contract() -> None:
    assert safe_entry_reference("./images/card-001.png") == "images/card-001.png"
    assert safe_entry_reference("audio/theme.mp3") == "audio/theme.mp3"
    for unsafe in (
        "../secret.png",
        "images/../../secret.png",
        "/absolute/card.png",
        "C:\\cards\\one.png",
        "images//card.png",
    ):
        with pytest.raises(ValueError, match="unsafe"):
            safe_entry_reference(unsafe)


def test_imports_android_v2_layers_soundtrack_and_credits(tmp_path: Path) -> None:
    pack = tmp_path / "language.zip"
    assets = tmp_path / "assets"
    _make_pack(pack)

    result = import_megapack(pack, assets)

    assert result.pack_name == "Language MegaPack"
    assert result.extracted_files == 3
    assert result.warnings == ()
    assert len(result.project.cards) == 1
    card = result.project.cards[0]
    assert card.title == "Breathing"
    assert card.value == "10 SECONDS OLD"
    assert card.description == "A baby's first breath."
    assert result.project.settings.model_id == "what-males-learn-at-each-age"
    assert result.project.settings.show_badges is False
    assert result.project.settings.soundtrack_volume == pytest.approx(0.8)
    assert result.project.settings.soundtrack_loop is False
    assert Path(result.project.settings.soundtrack).read_bytes().startswith(b"ID3")
    assert result.project.settings.credits_project_name == "Research · Ethan"
    assert result.project.settings.credits_created_with_value == "Artwork · Cubical Network"
    assert result.project.settings.end_credit_value == "Cubical Network"

    with Image.open(card.image) as artwork:
        artwork.load()
        assert artwork.size == (471, 872)
        corner = artwork.getpixel((5, 5))
        centre = artwork.getpixel((artwork.width // 2, artwork.height // 2))
        assert corner[1] > corner[0]
        assert centre[0] > centre[1]


def test_cli_import_writes_ccx_and_finishes_progress(tmp_path: Path) -> None:
    pack = tmp_path / "language.zip"
    output = tmp_path / "project.ccx"
    assets = tmp_path / "assets"
    progress = tmp_path / "progress.txt"
    _make_pack(pack)

    args = engine_cli.parser().parse_args([
        "import-megapack",
        str(pack),
        str(output),
        str(assets),
        "--progress-file",
        str(progress),
    ])
    assert args.func(args) == 0

    project = engine_cli.read_ccx(output)
    assert project.name == "Language MegaPack"
    assert project.cards[0].value == "10 SECONDS OLD"
    assert project.settings.show_badges is False
    assert Path(project.cards[0].image).is_file()
    assert progress.read_text(encoding="utf-8").startswith("100 ")


def test_missing_manifest_rejects_and_cleans_destination(tmp_path: Path) -> None:
    pack = tmp_path / "invalid.zip"
    assets = tmp_path / "assets"
    with zipfile.ZipFile(pack, "w") as archive:
        archive.writestr("cards/image.png", b"not an image")

    with pytest.raises(ValueError, match="missing megapack.json"):
        import_megapack(pack, assets)
    assert not assets.exists()
