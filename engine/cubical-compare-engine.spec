# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path
import hashlib
import urllib.request

from PyInstaller.utils.hooks import collect_submodules


# 2.0.4 uses the Poppins family visually matched against the supplied
# WatchData reference. Font binaries are intentionally not committed to CTS:
# fetch the exact official google/fonts blobs at build time and verify the Git
# blob SHA-1 before they enter the private renderer package.
FONT_ROOT = Path("ccengine") / "fonts"
FONT_ROOT.mkdir(parents=True, exist_ok=True)
FONT_FILES = {
    "Poppins-ExtraBold.ttf": (
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-ExtraBold.ttf",
        "167667d203d98f5b27c3ff58d486eea9c5287fe4",
    ),
    "Poppins-Bold.ttf": (
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-Bold.ttf",
        "1982f38ab21303459aa1155265052ca599fa58d1",
    ),
    "Poppins-SemiBold.ttf": (
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-SemiBold.ttf",
        "c30ad104723a0e6e00e54768626cb02c5fdf6aee",
    ),
    "Poppins-Medium.ttf": (
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-Medium.ttf",
        "a590f5c3e4902a7cb10f4bbc5da0e65e667f7950",
    ),
}


def git_blob_sha1(payload: bytes) -> str:
    prefix = f"blob {len(payload)}\0".encode("ascii")
    return hashlib.sha1(prefix + payload).hexdigest()


def ensure_font(filename: str, url: str, expected_blob: str) -> Path:
    destination = FONT_ROOT / filename
    if destination.is_file():
        data = destination.read_bytes()
        if git_blob_sha1(data) == expected_blob:
            return destination
        destination.unlink()
    with urllib.request.urlopen(url, timeout=30) as response:
        data = response.read()
    actual = git_blob_sha1(data)
    if actual != expected_blob:
        raise RuntimeError(
            f"Official Poppins font verification failed for {filename}: {actual}"
        )
    destination.write_bytes(data)
    return destination


font_paths = [ensure_font(name, *spec) for name, spec in FONT_FILES.items()]
license_path = FONT_ROOT / "OFL.txt"
if not license_path.is_file():
    raise RuntimeError("Poppins OFL license notice is missing from the engine source tree.")

hidden = collect_submodules("ccengine")
font_datas = [(str(path), "ccengine/fonts") for path in font_paths]
font_datas.append((str(license_path), "ccengine/fonts"))

a = Analysis(
    ["engine_cli.py"],
    pathex=["."],
    binaries=[],
    datas=font_datas,
    hiddenimports=hidden,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["PySide6", "PyQt6"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="cubical-compare-engine",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="cubical-compare-engine",
)
