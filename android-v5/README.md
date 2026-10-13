# Cubical Compare V5 Android — fresh native edition

This is a new native Android application under `android-v5/` on the `android-main-preview` branch. The previous `android/` app and all Windows sources are unchanged.

## Seven floating menus

All seven menus are available from a compact floating top navigation island: **Project, Cards, Thumbnails, Audio, Export, Draw, Settings**. On portrait phones the 16:9 live preview sits above the editing panel; landscape and large screens display preview and inspector side by side. It uses dark/light/system appearance with an Android 12+ dynamic colour accent, and native CPU Canvas drawing for older graphics devices.

Project imports/exports the same V5 `.ccv5` JSON field names as Windows and imports quoted multi-line CSV data. Cards can create, duplicate, reorder, edit, recolour and delete cards, including artwork drag/pinch/rotate/flip and exact transform controls. Thumbnails have **separate** artwork, independent image transformations and 1920x1080 PNG export, composed from the same Canvas code as the preview. Draw mode allows brush and eraser ink on either card artwork or thumbnails.

Audio supports ordered multiple tracks, move up/down, preview, remove, re-link, save as `.m3u`, import `.m3u`, playlist repeat, volume and end fade. The Android Media3 video exporter creates static timed frames from the editor artwork and encodes MP4 with audio in the selected order using H.264/AAC where the device supports the codecs. Frames per second, seconds per card and export size are adjustable.

## Platform differences

Windows' DWM Mica does not exist on Android. This replacement uses floating tonal surfaces and dynamic Android colour rather than claiming to copy the Windows API. WPF InkCanvas ISF stroke bytes are retained in project files but cannot be displayed by Android; Android native drawing is saved as its own PNG overlay fields, which the Windows preview currently does not render. Windows filesystem paths and Android `content://` audio references are not interchangeable across operating systems; relink audio when transferring projects and M3U patterns between devices.

The V5 project format is compatible at the *model* level; the designs are platform-adapted. CSV image file references from a Windows machine must be selected manually with Android's document picker. The codec pipeline relies on supported Android hardware media codecs even when software Canvas preview is active.

## Installation and releases

Android 8.0+ (API 26), including Android 11. The GitHub Actions workflow `android-v5-main-preview.yml` uses SDK 36, Gradle 8.13 and JDK 17, executes unit tests, builds a **debug-signed** sideload APK and publishes it as an individual GitHub prerelease. No background or silent installer is used. Play Store release signing is not configured.

Local build:

```bash
gradle --project-dir android-v5 :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

The app uses Storage Access Framework document pickers rather than storage-wide permissions and does not require access to the network. No built-in changelog or What's New dialog appears on app update.
