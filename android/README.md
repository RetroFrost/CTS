# CTS Android

Native Android port of **Comparison Timeline Studio**, built with Kotlin and Jetpack Compose.

## Current alpha scope

Implemented now:

- Premiere-inspired dark mobile editor shell
- flagship **Click to Insert Data** workflow
- Separate **What Males Learn at Each Age** and **Types of Relationships** models
- **Exact Reference** mode locks each source-derived motion profile; **Custom** unlocks timing
- Relationships uses the measured 60 fps frame contract (374-frame brand intro, frame-896 conveyor, 11,130-frame canonical 40-card timeline)
- Optional intro, disclaimer, badges, and ending/fade without changing editable card content
- CTS desktop timing: 2-second reveals and 10/3-second card scrolling
- parent-card → child-image-subcard scene hierarchy
- one independently defined image frame for every parent card
- touch drag and four-corner resize for image subcards
- image replacement without resetting the subcard transform
- local image picker and HTTP(S) image loading
- card add, duplicate, delete, and direct text editing
- timeline play/pause and scrubbing
- open/save `.cts.json`
- desktop-compatible spreadsheet/settings project payload
- migration support for CTS transform metadata
- unit tests for timeline timing
- GitHub Actions debug APK build

Not implemented yet:

- MediaCodec MP4 export
- soundtrack editor and audio mixing
- XLSX import on-device

The renderer and editor are deliberately being validated before the MP4 encoder is attached. The Android architecture does **not** reuse the desktop monitor-overlay transform stack. Each image is a child subcard owned by exactly one parent card.

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
- Version: `0.1.0-alpha1`
