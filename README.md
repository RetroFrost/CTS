# CVM — Comparison Video Maker

![Version](https://img.shields.io/github/v/release/RetroFrost/CTS?display_name=tag&sort=semver)
![Desktop](https://img.shields.io/badge/desktop-Python%20%2B%20PySide6-41cd52)
![Android](https://img.shields.io/badge/android-Kotlin%20%2B%20Compose-3ddc84)
![License](https://img.shields.io/badge/license-CC0-lightgrey)

**CVM (Comparison Video Maker)** is a cross-platform tool for creating continuously scrolling comparison videos from structured data, artwork, and optional audio.

Instead of manually building every card and animation in a video editor, CVM turns rows of data into a synchronized animated timeline. The Android and desktop editions share the same project model, card structure, animation timings, layout rules, and compatibility contract so a project behaves consistently across platforms.

## What CVM does

CVM is designed around a simple idea: provide the comparison data, choose or edit the presentation, and export the finished video.

It supports:

- CSV-style comparison data;
- image artwork for individual cards;
- optional soundtrack audio;
- automatic video timing;
- custom target video lengths;
- continuously scrolling comparison timelines;
- animated badges, cards, wipes, holds, and fades;
- synchronized Android and desktop project behavior;
- manual editing when the automatic workflow is not enough;
- MP4 export and live preview;
- compatibility with projects created using older CVM/CTS model identifiers.

## Basic workflow

1. Paste or import comparison data.
2. Review the generated cards.
3. Add artwork and optional music.
4. Use automatic timing or set a custom video duration.
5. Preview the result.
6. Adjust individual cards or timing when needed.
7. Export the completed comparison video.

A basic dataset looks like this:

```csv
Highlight,Highlight Image,Title,Description,Image
3.37 km²,highlight/batman.png,Batman: Arkham Knight,Gotham City was built with aggressive vertical density,maps/batman.png
3.7 km²,highlight/assassins.png,Assassin's Creed Syndicat,Pushed city density further,maps/assassins.png
```

This layout mirrors the comparison-table format used by the editor. `Highlight` is the primary badge value. `Title`, `Description`, and `Image` become the card fields. `Highlight Image` is optional; when both image columns exist, `Image` is used for the card artwork, while `Highlight Image` remains available as table data. If `Image` is omitted, `Highlight Image` can be used as the artwork fallback.

Every field is optional. Each data row becomes one comparison card.

For artwork, `Image` may contain either a local file path or a web URL. Local relative paths are resolved relative to the CSV file, so `maps/batman.png` works without an absolute path. A separate URL-style column is optional: headers such as `URL`, `Web URL`, `Web Image URL`, `Artwork URL`, and `Image URL` are automatically recognized as the card artwork source.

Example with an optional web URL:

```csv
Badge Value,Title,Web URL
42,Remote card,https://example.com/card.png
```

Example without a web URL:

```csv
Badge Value,Title,Image
42,Local card,art/card.png
```

## Reference Timeline

The current canonical presentation is the **Reference Timeline**.

Its shared behavior includes:

- four equal visible columns;
- left-to-right opening wipes;
- staggered card reveals;
- a short hold after the opening viewport fills;
- Material-eased one-card horizontal scrolling;
- animated badge entrances;
- ending hold and fade sequences;
- whole-animation scaling when a custom target duration is used;
- image transforms scoped to their own cards.

Desktop preview and desktop MP4 export use the same renderer. Android uses the corresponding shared project model and timing engine so both implementations follow the same visual contract.

## Cross-platform contract

The editable source of truth for shared Android/desktop behavior is:

```text
shared/cts_contract.json
```

The filename retains the original CTS project identifier for compatibility.

The contract defines:

- canonical model identifiers;
- visible-card count;
- legacy model compatibility;
- project version and card fields;
- reveal, scroll, hold, fade, wipe, and badge timings;
- animation easing;
- normalized image, title, description, and badge geometry;
- shared colors;
- starter data.

Generated platform adapters are located at:

```text
comparison_studio/shared_contract.py
android/app/src/main/java/io/github/retrofrost/cts/android/shared/SharedContract.kt
```

After changing the contract, regenerate both adapters:

```bash
python tools/sync_shared_contract.py
```

To check for drift without modifying files:

```bash
python tools/sync_shared_contract.py --check
```

GitHub Actions verifies that the generated adapters and shared renderer behavior remain synchronized.

## Android and desktop

CVM intentionally keeps native implementations on both platforms.

### Desktop

The desktop application uses **Python + PySide6** and provides the editing, preview, and export workflow.

### Android

The Android application uses **Kotlin + Jetpack Compose** with the same shared project semantics and timing rules.

Because the UI and renderer implementations are native to each platform, a renderer or interaction change may require corresponding work on both Android and desktop rather than a direct source-code translation.

## Desktop installation

Requirements:

- Python 3.10 or later;
- FFmpeg;
- system fonts required by the renderer.

On Debian/Ubuntu-based systems:

```bash
sudo apt update
sudo apt install python3-venv ffmpeg fonts-urw-base35

git clone https://github.com/RetroFrost/CTS.git
cd CTS

python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python run.py
```

Do not use `sudo pip` or `--break-system-packages`.

## Android build

The Android source is located in `android/`.

Requirements:

- JDK 17;
- Gradle 8.13 or a compatible wrapper/environment.

Run the Android tests:

```bash
gradle --project-dir android :app:testDebugUnitTest
```

Build a debug APK:

```bash
gradle --project-dir android :app:assembleDebug
```

The APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Validation

Before committing shared renderer or contract changes, run:

```bash
python tools/sync_shared_contract.py --check
python -m unittest discover -s tests -v
gradle --project-dir android :app:testDebugUnitTest
```

CI additionally checks platform parity, compiles the Python source tree, runs the Android tests/build, and runs the desktop unit and offscreen UI test suites.

## Project structure

```text
shared/cts_contract.json                    Cross-platform source of truth
tools/sync_shared_contract.py               Contract generator and parity checker
comparison_studio/shared_contract.py        Generated desktop contract adapter
comparison_studio/reference_illustrated.py  Canonical desktop renderer
comparison_studio/easy_timing.py            Shared-compatible desktop timing
comparison_studio/csv_text_easy.py          Desktop data workflow
android/.../shared/SharedContract.kt         Generated Android contract adapter
android/.../model/CtsProject.kt              Android project/card model
android/.../timeline/TimelineEngine.kt       Android timing behavior
android/.../ui/ProgramMonitor.kt             Native Compose renderer
```

Some internal paths and class names still contain **CTS**. They are retained for compatibility and do not change the public **CVM** product name.

See [Platform parity](docs/platform-parity.md) for contribution and synchronization rules.

## Project history

CVM was previously named **CTS — Comparison Timeline Studio**. The public project name has changed to **Comparison Video Maker** because it more directly describes the purpose of the application.

Existing internal identifiers may continue to use `CTS` while the rename is completed without breaking project compatibility.

## License

CVM is released under [CC0 1.0 Universal](LICENSE).
