# CTS Android

Native Android port of **Comparison Timeline Studio**, built with Kotlin and Jetpack Compose.

## Current alpha scope

Implemented now:

- Premiere-inspired dark mobile editor shell
- flagship **Click to Insert Data** workflow
- Separate **What Males Learn at Each Age** and **Types of Relationships** models
- **Exact Reference** mode locks each source-derived motion profile and plays it at 0.5× speed; **Custom** unlocks timing
- Relationships uses the measured 60 fps frame contract (374-frame brand intro, frame-896 conveyor, 11,130-frame canonical 40-card timeline)
- Optional intro, disclaimer, badges, and ending/fade without changing editable card content
- CTS desktop timing: 2-second reveals and 10/3-second card scrolling
- parent-card → child-image-subcard scene hierarchy
- one independently defined image frame for every parent card
- touch drag and four-corner resize for image subcards
- image replacement without resetting the subcard transform
- local image picker and HTTP(S) image loading
- one-image card-strip import with pixel-based orientation and edge recognition, draggable uneven boundaries, blank/duplicate warnings, and per-card focus/zoom previews
- safe MegaPack `.zip` import for a complete model, card dataset, artwork set, and optional soundtrack, with image validation and quality warnings
- card add, duplicate, delete, and direct text editing
- timeline play/pause and scrubbing
- open/save `.cts.json`
- desktop-compatible spreadsheet/settings project payload
- migration support for CTS transform metadata
- on-device XLSX import
- soundtrack selection, looping, volume, AAC bitrate, and encoder selection
- native H.264/HEVC MP4 export
- foreground WorkManager export with persistent progress, cancellation, and cleanup of incomplete output
- regression tests for the measured Males and Relationships timing, conveyor motion, and badge geometry
- GitHub Actions debug APK build

The Android preview and background exporter use the same timing engine. Each image is a child subcard owned by exactly one parent card, so its crop, position, and size remain attached to the card during animation and export.

## MegaPack format

A MegaPack is a ZIP with `megapack.json` at its root and referenced media stored inside the archive. Importing it creates a complete project ready for preview and export.

```json
{
  "version": 1,
  "name": "Example comparison",
  "model": "males",
  "model_mode": "exact_reference",
  "cards": [
    {
      "badge_primary": "10",
      "badge_secondary": "SECONDS OLD",
      "title": "Breathing",
      "description": "A baby's first breath.",
      "image": "images/001.png",
      "crop_focus_x": 0.5,
      "crop_focus_y": 0.45,
      "crop_zoom": 1.1
    }
  ],
  "soundtrack": {
    "file": "audio/theme.mp3",
    "volume": 1.0,
    "loop": true
  }
}
```

`model` accepts `males` or `relationships`. Crop fields are optional: focus values range from 0 to 1 and zoom ranges from 1 to 3. Soundtrack is optional. Referenced ZIP paths must be relative. Limits are 1 GB for the ZIP, 512 MB extracted, 64 MB per file, 1,000 entries, and 500 cards.

## Build on Ubuntu

From the repository root:

```bash
cd android
chmod +x gradlew
./gradlew :app:assembleDebug
```

The launcher downloads the pinned Gradle 8.13 distribution into `~/.gradle/cts-wrapper` on first use.

The APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install it with ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Open in Android Studio

Open the `android/` directory as the project. Use JDK 17 and Android SDK 36.

## Project compatibility

CTS Android writes the desktop `spreadsheet`, `settings`, `transform_overrides`, and `transform_space` keys, then adds Android parent/child identity metadata under the `android` key. Desktop CTS can ignore the extra metadata while retaining the normal card data and transforms.

## Package

- Application ID: `io.github.retrofrost.cts.android`
- Visible name: `CTS Android`
- Developer branding: `StarterFreaks`
- Version: `0.3.0-alpha3`
