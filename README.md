# Cubical Compare 1.0

Cubical Compare is a native desktop editor for creating animated comparison videos from spreadsheet data, images, text, and music.

The Windows and Linux applications use the same deterministic rendering engine for preview and MP4 export. That engine is bundled privately inside every package: users install and launch only Cubical Compare.

## Reference model

Cubical Compare contains one locked reproduction model:

- **What Males Learn At Each Age**

The supplied **Comparison: Evolution Of Language (400,000 BC–2026)** reference video is the source of truth. The model is not an approximate theme or editable animation preset. Its 57-card scene order, integer-frame motion, 476-pixel slot pitch, white title bands, badge deformation, credits movement, conveyor, outro, 1920×1080 output, and 60 FPS clock are model-owned and immutable.

Users replace the content carried by the model—titles, values, descriptions, images, card order, music, credits text, and end-screen text. They cannot add, remove, reorder, shorten, lengthen, or restyle model animation.

Projects persist a model ID and revision lock. Legacy project values that attempt to select a removed model or change model geometry, frame rate, cadence, or revision are normalised back to the Males reference.

## Workflow

1. **Use the Males reference model** — it locks all animation mechanics.
2. **Click to Insert Data** — import CSV or XLSX data and replace the active cards.
3. **Image Sheet** — split a continuous or gridded sheet into durable per-card assets.
4. **Music** — select a soundtrack and configure looping, volume, offset, and fade-out.
5. **Preview** — inspect the same integer source frames used by export.
6. **Export MP4** — export with visible progress, cancellation, validation, and atomic output replacement.

The manual editor supports direct card editing, image transforms, credits, fonts, fit modes, and encoding settings. Model timing and layout remain read-only.

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

Cubical Compare 1.0 preserves the `CCX1` project format used by the final pre-1.0 development builds and adds a locked model identity and revision. Portable project saves collect referenced images, soundtrack files, and fonts into a relative asset directory.

## Reliability work in 1.0

- Preview and export use the same integer 60 FPS frame clock.
- Exact source frames can be rendered through the private engine CLI.
- Spreadsheet imports apply immediately and run outside the interface thread.
- Numeric zero and Boolean false survive import; header detection does not delete a valid first card.
- Relative spreadsheet image paths resolve from the spreadsheet directory.
- Image-sheet assets use durable storage and preserve per-card transforms.
- Portable saves collect images, soundtrack files, and fonts.
- Encoder presets are validated and respected.
- Missing soundtrack files stop export with a readable error.
- Export uses bounded parallel rendering, bounded caches, continuously drained FFmpeg diagnostics, and responsive cancellation.
- Closing the editor terminates its private engine and FFmpeg children.
- Existing output files are replaced only after a successful export.
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

List the built-in models:

```text
python engine/engine_cli.py list-models
```

Render one exact model frame:

```text
python engine/engine_cli.py render-preview project.ccx frame.png --frame 374
```

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

- all engine and model-lock tests
- frame-addressable preview/export parity
- the frozen private engine
- native Windows and GTK builds
- native GUI self-tests
- real H.264 MP4 creation and inspection
- Windows portable ZIP packaging
- Linux TAR.GZ and Debian packaging
- Flatpak manifest and repository linting
- installation and execution inside the Flatpak sandbox
- absence of broad home or host filesystem permissions

See `engine/models/README.md` for the frame-level model conformance process.
