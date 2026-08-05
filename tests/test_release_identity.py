from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP_ID = "io.github.retrofrost.CTS"


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def test_native_product_identity_is_final() -> None:
    native_text = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in (ROOT / "native").rglob("*")
        if path.is_file()
    )
    legacy_values = (
        "Cubical" + " Create",
        "Cubical" + "Create",
        "Cubical" + ".Create",
        "network.cubical" + ".Create",
        "cubical" + "-create",
        "CUBICAL" + "_CREATE_ENGINE",
    )
    for legacy in legacy_values:
        assert legacy not in native_text

    assert APP_ID in read("native/linux-gtk/main.cpp")
    assert 'name="Cubical.Compare"' in read("native/windows/CubicalCompare.manifest")
    assert 'version="1.0.0.0"' in read("native/windows/CubicalCompare.manifest")


def test_flatpak_exports_only_cubical_compare() -> None:
    packaging = ROOT / "packaging" / "flatpak"
    desktop_files = sorted(packaging.glob("*.desktop"))
    metainfo_files = sorted(packaging.glob("*.metainfo.xml"))
    assert [path.name for path in desktop_files] == [f"{APP_ID}.desktop"]
    assert [path.name for path in metainfo_files] == [f"{APP_ID}.metainfo.xml"]

    manifest = read(f"packaging/flatpak/{APP_ID}.yml")
    desktop = read(f"packaging/flatpak/{APP_ID}.desktop")
    metainfo = read(f"packaging/flatpak/{APP_ID}.metainfo.xml")

    assert f"app-id: {APP_ID}" in manifest
    assert "command: cubical-compare" in manifest
    assert "--filesystem=home" not in manifest
    assert "--filesystem=host" not in manifest
    assert "flatpak-spawn" not in manifest
    assert "/app/libexec/cubical-compare/engine/cubical-compare-engine" in manifest
    assert "Exec=cubical-compare %F" in desktop
    assert f"Icon={APP_ID}" in desktop
    assert f"<id>{APP_ID}</id>" in metainfo
    assert f"<launchable type=\"desktop-id\">{APP_ID}.desktop</launchable>" in metainfo


def test_flatpak_python_sources_are_offline_and_pinned() -> None:
    generated = read("packaging/flatpak/python3-cubical-compare-engine.json")
    assert '"name": "python3-pybind11"' in generated
    assert "pybind11==3.0.4" in generated
    assert "Pillow==12.3.0" in generated
    assert "openpyxl==3.1.5" in generated
    assert "PyInstaller==6.21.0" in generated
    assert "pytest==9.1.1" in generated
    assert '"sha256"' in generated
    assert "--no-index" in generated
