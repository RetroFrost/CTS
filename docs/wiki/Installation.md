# Installation

## Install an APK

1. Download the current Cubical Compare APK from a verified GitHub Actions artifact or release.
2. Open the APK on the Android device.
3. Allow installation from the source app if Android asks.
4. Install Cubical Compare.

Cubical Compare 2.0.7 targets the native Android fork and does not require Python, Termux or a desktop companion.

## Android requirements

- Android device with modern MediaCodec support.
- Hardware video encoding is preferred for export.
- H.264 is the safest compatibility choice; H.265 can be faster or smaller on supported devices.
- Enough free storage for the source artwork, project data and the final MP4.

## Upgrading

Installing a newer build over an existing one should preserve normal app data when the package identity is unchanged. Keep important `.ccproject.json`, MegaPack and renderer bundle files outside the app's private storage as well.

## First launch

Cubical Compare opens into the main editor with four areas:

- **Cards** — edit card data and artwork.
- **Preview** — play/scrub and directly transform artwork on the rendered frame.
- **Project** — duration, intro, soundtrack, badges and encoder preferences.
- **More** — project files, imports, renderer library and export.

## Installing a renderer

Open **More → Renderer** to import a `.renderer` bundle. Installed renderers appear in the renderer library.

## Importing a MegaPack

Open **More → MegaPack** and choose the `.zip` MegaPack. Cubical Compare extracts its data and artwork into app-managed storage and creates the project cards.
