# Cubical Compare 1.0 Final

Cubical Compare is the final native comparison-video editor for Windows, backed by the same deterministic rendering engine used for preview and MP4 export.

## Normal workflow

1. **Click to Insert Data** — import CSV/XLSX and immediately replace the active cards.
2. **Image Sheet** — split a continuous or gridded sheet into durable per-card assets.
3. **Music** — choose a soundtrack, loop, volume, offset and fade.
4. **Length** — use automatic source timing or enter a fixed total duration.
5. **Export MP4** — foreground progress, cancellation, validation and atomic output.

The **Manual editor** remains available for direct card editing, free image transforms, credits, fonts, fit mode and output settings.

## Final-state fixes

- Spreadsheet imports apply immediately and run outside the Windows UI thread.
- Numeric zero and Boolean false survive import; header detection no longer deletes a valid first card.
- Relative spreadsheet image paths resolve from the spreadsheet folder.
- Image-sheet assets use durable storage, preserve transforms, calculate the remaining expected count and can create missing cards.
- Music and video-length controls are directly accessible from the main toolbar.
- Project saves collect images, soundtrack and font files into a portable asset folder.
- Upper-case project extensions work.
- Output dimensions are normalised to legal even values; encoder presets are validated and respected.
- Missing soundtrack files stop export with an error instead of silently removing audio.
- Export uses bounded parallel Pillow rendering, bounded caches, reused static frames, continuously drained FFmpeg diagnostics and responsive cancellation.
- Image transforms are memory-capped; front-layer artwork truly renders above text and badges.
- Non-16:9 output is letterboxed instead of stretched.
- Corrupt CCX base64 and unsafe numeric values are rejected or safely normalised.
- Closing during a task cancels and then closes automatically.
- Startup shows a visible early frame rather than the intentionally black frame at exactly 0.0 seconds.

## Windows package

Extract the complete ZIP into a fresh folder and run `CubicalCompare.exe`. Keep these files together:

- `CubicalCompare.exe`
- `cubical-compare-engine.exe`
- `ffmpeg.exe`

## Build from source

Use Python 3.12+, CMake, a C++20 compiler, Pillow, openpyxl, pytest and FFmpeg.

```text
python -m pip install -r engine/requirements.txt pytest pyinstaller
set PYTHONPATH=engine
pytest -q
cmake -S . -B build -A x64
cmake --build build --config Release
```
