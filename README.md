# Cubical Compare 1.0

Cubical Compare is a native desktop editor for creating animated comparison videos from spreadsheet data, images, text, and music.

The Windows and Linux applications use the same deterministic rendering engine for preview and MP4 export. That engine is bundled privately inside every package: users install and launch only Cubical Compare.

## Workflow

1. **Click to Insert Data** — import CSV or XLSX data and replace the active cards.
2. **Image Sheet** — split a continuous or gridded sheet into durable per-card assets.
3. **Music** — select a soundtrack and configure looping, volume, offset, and fade-out.
4. **Length** — use automatic timing or set a fixed total duration.
5. **Export MP4** — export with visible progress, cancellation, validation, and atomic output replacement.

The manual editor supports direct card editing, image transforms, credits, fonts, fit modes, and output settings.

## Packages

### Windows x64

Extract the complete archive and launch:

```text
CubicalCompare.exe
```

Keep the extracted directory together. The private renderer and FFmpeg are stored under:

```text
libexec/engine/
```

They are implementation details and are not separate applications.

### Linux amd64

The release provides:

- a Debian package
- a portable TAR.GZ package
- a Flatpak bundle for local testing

The Debian and portable packages install the private engine under:

```text
libexec/cubical-compare/engine/
```

The user-facing command is always:

```text
cubical-compare
```

### Flatpak and Flathub

The Flatpak application ID is:

```text
io.github.retrofrost.CTS
```

The product name shown to users remains **Cubical Compare**.

The Flatpak contains one exported command and one desktop entry. Its rendering helper runs inside the same sandbox at:

```text
/app/libexec/cubical-compare/engine/cubical-compare-engine
```

The package does not request broad home-directory or host-filesystem access. User-selected files are accessed through the desktop file portals.

## Project compatibility

Cubical Compare 1.0 preserves the `CCX1` project format used by the final Cubical Create development builds. Portable project saves collect referenced images, soundtrack files, and fonts into a relative asset directory.

## Reliability work in 1.0

- Spreadsheet imports apply immediately and run outside the interface thread.
- Numeric zero and Boolean false survive import; header detection does not delete a valid first card.
- Relative spreadsheet image paths resolve from the spreadsheet directory.
- Image-sheet assets use durable storage and preserve per-card transforms.
- Music and video-length controls are directly available from the main toolbar.
- Portable saves collect images, soundtrack files, and fonts.
- Output dimensions are normalised to legal even values.
- Encoder presets are validated and respected.
- Missing soundtrack files stop export with a readable error.
- Export uses bounded parallel rendering, bounded caches, continuously drained FFmpeg diagnostics, and responsive cancellation.
- Closing the editor terminates its private engine and FFmpeg children.
- Existing output files are replaced only after a successful export.
- Non-16:9 output is letterboxed rather than stretched.
- Corrupt project data and unsafe numeric values are rejected or safely normalised.

## Build from source

Required tools:

- Python 3.12 or newer
- CMake 3.22 or newer
- a C++20 compiler
- GTK 4 development files on Linux
- FFmpeg

Install the pinned engine and test dependencies:

```text
python -m pip install -r engine/requirements-build.txt
```

Run the engine tests:

```text
set PYTHONPATH=engine
pytest -q
```

On Linux, use `export PYTHONPATH=engine` instead.

Build the private engine:

```text
cd engine
pyinstaller --noconfirm --clean cubical-compare-engine.spec
cd ..
```

Build the Linux application:

```text
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

Build the Windows application from a Visual Studio developer environment:

```text
cmake -S . -B build -A x64
cmake --build build --config Release
```

## Release validation

The release workflows verify:

- all engine unit tests
- the frozen private engine
- native Windows and GTK builds
- native GUI self-tests
- real H.264 MP4 creation and inspection
- Windows portable ZIP packaging
- Linux TAR.GZ and Debian packaging
- Flatpak manifest and repository linting
- installation and execution inside the Flatpak sandbox
- absence of broad home or host filesystem permissions
