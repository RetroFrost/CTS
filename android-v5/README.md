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


## Native architecture update (Rust + Material Design 3)

The launcher is now `ComposeStudioActivity.kt` (Kotlin Jetpack Compose **Material 3** with dynamic colours, rounded floating navigation island, and separate seven-screen editing controls). The old hand-built Java activity is not the launcher; Java `StudioModel`, `CardCanvas`, and Media3 `VideoExporter` remain as interoperability modules while the port is migrated.

`android-v5/rust/` is a native Cargo crate compiled to `libcts_engine.so` for arm64-v8a, armeabi-v7a, and x86_64. JNI exposes a deterministic integer-frame comparison timeline, eased card-conveyor motion and badge-scale timing. Rust host unit tests verify repeatable easing and final-card behaviour. The Android editor calls Rust on startup, and Compose playback uses the Rust scene geometry; the Media3 exporter composes motion frames from those coordinates. Gradle builds an APK containing all three ABI libraries. Android 11 emulator checks launch the Compose activity.

**Parity caveat:** This is a functional new editing core, not a frame-accurate port of every legacy `.renderer3` feature, badge ZIP animation, soundtrack waveform feature or Windows DWM/Mica API. The existing Android Canvas generates artwork with its own raster layer, with project fields kept compatible where possible. Rust timeline motion is an actual engine, not a Java stub, but the complete Renderer3 subsystem must still be ported separately.

## Renderer3 import and execution

Open **Project → Import Renderer3** or **Renderers → Import .renderer3 / Renderer3 ZIP**. Android's document picker copies the selected file into private app storage after validating it. Imported packages can be selected, disabled or deleted. The chosen Renderer3 filename and ID are saved in .ccv5 projects; copying a project to another device requires reimporting its renderer assets.

This V5 implementation reuses the actual Android Renderer3 format/player originally present in the repository, rather than introducing a fake renderer picker:

- RendererV3Container validates CCRNDR03 magic, version, length, CRC32, bounded gzip JSON, ZIP paths and contained assets. Unsupported engines and missing SmartBadge ZIP frames are rejected.
- RendererV3Player draws scene-v3 objects and delegates supported wrapped ribbon-exact animation and SmartBadge v2 frame sequences to RendererV3RibbonPlayer and SmartSequenceV3.
- Renderer3FrameView uses the frame-addressed renderer for the actual Material 3 preview, scrubbing and playback.
- Renderer3VideoEncoder feeds every frame to the Android H.264 MediaCodec encoder with bounded RAM (not thousands of temporary PNGs); the Media3 exporter handles the creator's ordered soundtrack pattern.
- Ported RendererV3ContainerTest and WrappedRibbonV3Test validate the format, CRC, unsafe paths, wrapped engine and SmartBadge sidecars.

**Compatibility:** Other Windows-only rendering engines, unknown required features and missing ZIP assets are not silently approximated. H.264 export depends on the device encoder and longer animations may be demanding. The standalone .renderer3 file is not embedded in .ccv5 project saves; reimport it on other devices.

## Creator workflow & portable projects

- **Project → Export complete .ctsv5** packages the entire project, the imported Renderer3 file and every ordered soundtrack in a single ZIP-based document; **Open complete .ctsv5** restores the project and local media references on another Android device. The project canvas artwork and separate thumbnail transforms already live in the .ccv5 JSON. Plain .ccv5 remains available for Windows data interchange but cannot package renderer/audio binaries.
- ZIP import enforces a strict path allowlist, rejects duplicate filenames and unexpected entries, and limits expanded data (512 MB total; 128 MB for Renderer3; 96 MB per soundtrack; 64 MB for project JSON). Invalid archives are not partially installed as the active project.
- When a Renderer3 is selected, **Cards** defaults to the directly editable artwork canvas; choose **Preview Renderer3** to compare the rendered output, then **Edit artwork directly** to return to touch editing. **Renderers** and **Export** always show the actual renderer output.
- The **Cards** list renders lazily in a bounded scroll area for 40–50+ cards, descriptions allow multiple lines, and **New project** asks for confirmation before replacing work.
- Work is autosaved after a short editing pause and on app pause. File writes happen on a background worker through an atomic replacement; unlike the original UI, a full project JSON is not written synchronously in onPause.

### Parity audit – not yet completed

The native Android implementation still lacks a general **Zipack2 MegaPack import/export** workflow (CSV imports work), arbitrary Windows **Renderer3 extension/plugin execution**, Windows patch/developer tools, fully portable cross-platform WPF ink layers, and a fully integrated live per-frame Renderer3 element inspector. Renderer support remains limited to the existing Android scene-v3/ribbon-exact feature set. No claim of complete stable Windows CTS parity is made. Feature labels are not substitutes for working engines.
