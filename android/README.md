# Cubical Compare V5 Android M2 — Renderer3

The Android V5 app imports actual binary `.renderer3` files (CCRNDR03 container)
and Renderer3 ZIP packages via **Project menu → Import Renderer3**. Renderer3 scene
selection is saved in Android project metadata; the imported file is safely copied
to private app storage and used by **both preview and MP4 export**, so the output
uses the same integer-frame animation evaluator shown in the monitor.

## Supported on Android M2

- Renderer3 API 3 `scene-v3` manifests (gzip + CRC32 verified), including ZIP sidecar assets.
- Absolute frame clock and output duration derived from the Renderer3 scene (independent of card count).
- Image/raster layers, vector rectangles, ellipses, polygons, text and layer ordering.
- Dense frame values, keyframed interpolation, selector overrides, clipping, transforms, opacity and sourceExact conveyor offsets.
- Safe ZIP member paths, archive limits and bounded source image decoding.
- A clear import error for Renderer3 features the mobile runtime cannot execute faithfully.
- Existing Android editors, cards, soundtrack and low-memory export presets remain available.
- Renderer3 files stay private to the app and may be reimported if app data is cleared.
- V5 uses a separate package ID from the stable Android application.

## Not yet available

This is a focused Renderer3 milestone, **not complete desktop Renderer3 parity**.
Windows-only wrapped legacy engines, project-card-data interpretation, full SmartBadge
v2/SmartCard animation sequences, groups and exact overlay resources are rejected at
import rather than being silently approximated. Embedded renderer audio is not
currently read from the Renderer3 package; use the app's soundtrack selector.

## Build and test

With JDK 17, Gradle 8.13 and Android SDK 36:

```bash
gradle --project-dir android :app:testDebugUnitTest :app:assembleDebug
```

GitHub Actions compiles, tests and publishes the Android M2 GitHub prerelease
with a **debug-signed APK**. An APK from a different signing key will need
reinstallation; permanent production signing is a future milestone.
