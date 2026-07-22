# CTS — Comparison Timeline Studio

![Version](https://img.shields.io/badge/version-0.5.0-6d55f7)
![Desktop](https://img.shields.io/badge/desktop-Python%20%2B%20PySide6-41cd52)
![Android](https://img.shields.io/badge/android-Kotlin%20%2B%20Compose-3ddc84)
![License](https://img.shields.io/badge/license-CC0-lightgrey)

CTS creates continuously scrolling comparison videos from CSV-style data. Version 0.5.0 brings the Android and desktop editions back together: both use the same canonical **Reference Timeline** design, project version, card fields, timing constants, animation curve, layout coordinates, compatibility IDs, colors, and starter data.

The desktop edition is no longer an older independent design. Historical desktop model IDs still load, but they normalize to the shared four-column Reference Timeline without discarding card content.

## Normal workflow

1. Paste CSV text with a header row.
2. Confirm the synchronized Reference Timeline design.
3. Add optional music.
4. Keep automatic timing or enter a target video length.
5. Export the video, or open the manual editor for detailed changes.

Recommended fields:

```csv
Badge Value,Badge Label,Title,Description,Artwork
10,SECONDS OLD,Breathing,A baby's first breath requires blood flow through the heart.,image.png
```

Every field is optional. Each following row becomes one card.

## One shared Android–desktop contract

The editable cross-platform source of truth is:

```text
shared/cts_contract.json
```

It defines:

- the canonical model ID and visible-card count;
- legacy model compatibility;
- project version and card fields;
- reveal, scroll, hold, fade, wipe, and badge timings;
- the Material easing curve;
- normalized image, title, description, and badge frames;
- shared colors and starter cards.

Generated adapters live at:

```text
comparison_studio/shared_contract.py
android/app/src/main/java/io/github/retrofrost/cts/android/shared/SharedContract.kt
```

After editing the JSON contract, regenerate both adapters:

```bash
python tools/sync_shared_contract.py
```

Check for drift without changing files:

```bash
python tools/sync_shared_contract.py --check
```

GitHub Actions rejects a pull request when either generated adapter, the Android Program Monitor geometry/colors, or the desktop shared behavior no longer matches the contract.

Compose and PySide6 remain native implementations. A platform-specific interaction or renderer-code change therefore needs a corresponding Android and desktop implementation in the same pull request; it cannot be safely translated from Kotlin to Python or vice versa by text generation alone.

## Canonical Reference Timeline

Both platforms now use:

- exactly four equal columns;
- left-to-right opening wipes two seconds apart;
- a short hold after the opening viewport fills;
- Material-eased one-card horizontal movement;
- oversized red badge entrances that settle into place;
- a two-second ending hold and 0.8-second fade;
- whole-animation scaling for a custom target duration;
- image transforms owned by their individual parent card.

Desktop preview and MP4 export resolve the same renderer. Android uses the corresponding shared project model and timing engine.

## Desktop installation

Requirements: Python 3.10 or later, FFmpeg, and system fonts.

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

The Android project is in `android/` and uses JDK 17 plus Gradle 8.13.

```bash
gradle --project-dir android :app:testDebugUnitTest
gradle --project-dir android :app:assembleDebug
```

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Validation

```bash
python tools/sync_shared_contract.py --check
python -m unittest discover -s tests -v
gradle --project-dir android :app:testDebugUnitTest
```

The platform-parity workflow also compiles the Python source tree. The Android workflow builds the APK and runs Kotlin tests whenever Android or the shared contract changes. The desktop workflow runs the complete unit and offscreen UI suite and preserves its full failure log as an artifact.

## Project structure

```text
shared/cts_contract.json                    Cross-platform source of truth
tools/sync_shared_contract.py               Generator and parity checker
comparison_studio/shared_contract.py        Generated desktop adapter
comparison_studio/reference_illustrated.py  Canonical desktop renderer
comparison_studio/easy_timing.py            Android-compatible desktop timing
comparison_studio/csv_text_easy.py          Synchronized desktop workflow
android/.../shared/SharedContract.kt         Generated Android adapter
android/.../model/CtsProject.kt              Shared project/card model
android/.../timeline/TimelineEngine.kt       Shared timing behavior
android/.../ui/ProgramMonitor.kt             Native Compose renderer
```

See [Platform parity](docs/platform-parity.md) for contribution rules.

## License

CTS is released under [CC0 1.0 Universal](LICENSE).
