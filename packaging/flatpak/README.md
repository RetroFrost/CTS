# Cubical Compare Flatpak

This directory contains the Flatpak and Flathub packaging for Cubical Compare 1.0.

## Application architecture

The package exposes one application and one command:

- Application ID: `io.github.retrofrost.CTS`
- Product name: Cubical Compare
- Command: `/app/bin/cubical-compare`

The Python rendering engine is a private helper installed at:

```text
/app/libexec/cubical-compare/engine/cubical-compare-engine
```

It is not exported as a desktop application, installed as another Flatpak, or presented as a user-facing command. The native editor launches it directly inside the same sandbox.

## Files

- `io.github.retrofrost.CTS.yml` — local source-tree build manifest
- `io.github.retrofrost.CTS.desktop` — desktop entry
- `io.github.retrofrost.CTS.metainfo.xml` — AppStream metadata
- `io.github.retrofrost.CTS.svg` — application icon
- `python3-cubical-compare-engine.json` — checksum-pinned offline Python build sources
- `requirements-flatpak.txt` — input used to regenerate the pinned Python module

## Canonical source

The release branch contains ordinary editable source directories:

- `native/`
- `engine/`
- `tests/`
- `packaging/`
- `CMakeLists.txt`

Historical Base64 overlays are not required to build Cubical Compare 1.0.

## Sandbox contract

The application requests display, audio, GPU, IPC, and network access. It does not request broad home-directory or host-filesystem access.

File opening and export destinations use GTK native file dialogs and desktop portals. Network access remains enabled for remote image importing.

The editor and private helper run inside the same sandbox and never use `flatpak-spawn --host`.

## Local validation

The Flatpak CI workflow performs all of the following:

1. Validates the final application identity and private-engine paths.
2. Runs the official Flathub manifest and AppStream linters.
3. Builds all dependencies from checksum-pinned declared sources.
4. Builds the native GTK application and private PyInstaller engine.
5. Installs the generated Flatpak into a temporary repository.
6. Verifies the sandbox permissions and one-desktop-entry contract.
7. Runs the GUI and rendering-engine self-tests inside the sandbox.
8. Verifies the generated H.264 MP4 with `ffprobe`.
9. Produces a single-file Flatpak test bundle and checksums.

The upstream manifest uses a local `type: dir` source for repository CI. A Flathub submission manifest must replace that source with the exact tested Git commit.
