# Cubical Compare Flatpak

This directory contains the Flatpak/Flathub packaging for Cubical Compare 1.0.

## Architecture

The Flatpak exposes one application and one command:

- App ID: `io.github.retrofrost.CubicalCompare`
- Command: `/app/bin/cubical-compare`

The Python rendering engine is a private helper installed at:

`/app/libexec/cubical-compare/engine/cubical-compare-engine`

It is not added to `PATH`, exported as a desktop application, or installed as a separate Flatpak. The native editor launches it directly inside the same sandbox using `CUBICAL_COMPARE_ENGINE` or the compiled default path.

## Files

- `io.github.retrofrost.CubicalCompare.yml` — local-development manifest
- `io.github.retrofrost.CubicalCompare.desktop` — desktop entry
- `io.github.retrofrost.CubicalCompare.metainfo.xml` — AppStream metadata
- `io.github.retrofrost.CubicalCompare.svg` — application icon

## Current migration state

The `cubical-create-1.0-final` code is still reconstructed from historical Base64 archive overlays by its Windows workflow. The release branch must first materialise that final state into the canonical repository tree:

- `native/`
- `engine/`
- `tests/`
- `packaging/`
- `CMakeLists.txt`

The Flatpak manifest deliberately targets that canonical tree. It must not become the submitted Flathub manifest until all Python and PyInstaller build inputs are pinned as offline `sources` with checksums and the application source is pinned to the exact `v1.0.0` commit.

## Sandbox contract

The manifest does not grant `--filesystem=home` or `--filesystem=host`. File selection and export destinations must use GTK native file dialogs and desktop portals. Remote image importing is why network access remains enabled.

The editor and helper must never use `flatpak-spawn --host`.
