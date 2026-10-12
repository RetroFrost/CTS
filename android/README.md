# Cubical Compare V5 — Android M1

This is the native Kotlin/Compose Android editor, ported from the previous Android branch into the isolated V5 development line. The V4 app is unchanged.

## Android M1 improvements

- Separate V5 application ID: `io.github.retrofrost.cts.android.v5` (can coexist with V4).
- Memory/codec-aware initial H.264 export profile; on Snapdragon 429-class devices the preferred default is 720p/30 when supported.
- Visible 720p/30, 1080p/30, and 1080p/60 output choices; animation duration and card geometry do not depend on export resolution.
- Existing Android import, MegaPack, soundtrack, project editing, rendering, background export and unit tests retained.
- GitHub Actions validates project parity and builds/tests the preview APK, then publishes a GitHub prerelease.

**M1 limitations:** The existing Android renderer is retained; Renderer3/Renderer4 support and V5 AI Project Builder integration are not yet ported. The prerelease uses Android debug signing, so future previews may require reinstalling until persistent release signing is configured.

## Build

Use JDK 17, Gradle 8.13 and Android SDK 36:

```bash
gradle --project-dir android :app:testDebugUnitTest :app:assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`.
