# Cubical Compare 2.0.7 for Android

Native Android editor and GPU video renderer, built with Kotlin and Jetpack Compose.

## Current scope

Implemented now:

- Redesigned dark mobile editor with Cards, Timeline, and Settings tabs
- always-visible preview with playback and scrubbing
- custom video length which retimes only the continuous card conveyor
- stationary opening badges with no bounce or motion streak
- measured vertical fall retained for later badges
- optional settled-scrolling mode that places every post-opening badge immediately at its final position
- flagship **Click to Insert Data** workflow
- One canonical **Males** reference model, measured from the complete Evolution of Language source
- **Exact Reference** mode locks each source-derived motion profile and plays it at 0.5× speed; **Custom** unlocks timing
- Native 1920×1080/60 FPS frame clock with a 12,267-frame canonical 57-card timeline
- built-in or user-selected MP4 intro, editable credits, disclaimer, badges, and ending/fade
- CTS desktop timing: 2-second reveals and 10/3-second card scrolling
- parent-card → child-image-subcard scene hierarchy
- one independently defined image frame for every parent card
- touch drag and four-corner resize for image subcards
- image replacement without resetting the subcard transform
- local image picker and HTTP(S) image loading
- one-image card-strip import with pixel-based orientation and edge recognition, draggable uneven boundaries, blank/duplicate warnings, and per-card focus/zoom previews
- safe MegaPack `.zip` import for a complete model, layered backgrounds/subjects, optional intro and soundtrack, with image validation and quality warnings
- card add, duplicate, delete, and direct text editing
- timeline play/pause and scrubbing
- open/save `.cts.json`
- desktop-compatible spreadsheet/settings project payload
- migration support for CTS transform metadata
- on-device XLSX import
- soundtrack selection, looping, volume, AAC bitrate, and encoder selection
- GPU OpenGL ES rendering with Android MediaCodec H.264/HEVC export
- automatic H.265-to-H.264 runtime fallback or user-selected H.264/H.265 encoder choice
- foreground-service export with persistent progress, cancellation, and cleanup of incomplete output
- export-time preview-cache release, bounded GPU textures, and recordable EGL configuration fallbacks
- regression tests for measured Males timing, conveyor motion, attached title bands, badge geometry, and outro
- GitHub Actions debug APK build

The Android preview and background exporter use the same timing engine. Each image is a child subcard owned by exactly one parent card, so its crop, position, and size remain attached to the card during animation and export.

## MegaPack format

A MegaPack is a ZIP with `megapack.json` at its root and referenced media stored inside the archive. Importing it creates a complete project ready for preview and export.

```json
{
  "version": 2,
  "name": "Example comparison",
  "model": "males",
  "model_mode": "exact_reference",
  "cards": [
    {
      "badge_primary": "10",
      "badge_secondary": "SECONDS OLD",
      "title": "Breathing",
      "description": "A baby's first breath.",
      "background": "backgrounds/001.jpg",
      "subject": "subjects/001.png",
      "crop_focus_x": 0.5,
      "crop_focus_y": 0.45,
      "crop_zoom": 1.1
    }
  ],
  "intro_video": {
    "file": "video/intro.mp4",
    "display_name": "Channel intro",
    "duration_seconds": 6.4
  },
  "credits": {
    "heading": "Credits",
    "lines": ["Research · Ethan", "Artwork · Cubical Network"],
    "footer": "SOURCES IN DESCRIPTION",
    "ending_heading": "Video Made By",
    "ending_details": "Cubical Network"
  },
  "soundtrack": {
    "file": "audio/theme.mp3",
    "volume": 1.0,
    "loop": true
  }
}
```

`model` accepts `males`. `background` fills the complete card; a transparent `subject` is composed above it but below the badge. Version-1 `image` entries remain supported as legacy single-layer artwork. Crop fields are optional: focus values range from 0 to 1 and zoom ranges from 1 to 3. Intro, credits, and soundtrack are optional. Referenced ZIP paths must be relative. Limits are 1 GB for the ZIP, 512 MB extracted, 64 MB per file, 1,000 entries, and 500 cards.

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

- Application ID: `dev.infinitycomparison.cc`
- Visible name: `Cubical Compare 2.0.7`
- Version: `2.0.7`
