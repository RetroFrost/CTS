# Building from Source

## Requirements

- JDK compatible with the Android Gradle configuration used by the repository.
- Android SDK/Build Tools required by the project.
- Git.
- A normal Android/Gradle development environment.

Python is not required for the Android runtime.

## Clone

```bash
git clone https://github.com/RetroFrost/CTS.git
cd CTS
git checkout fork/2.0.7-renderer-bundles
```

## Gradle wrapper

The Android project is under `android/` and uses its Gradle wrapper.

Run the same core build used by CI:

```bash
android/gradlew -p android :app:testDebugUnitTest :app:assembleRelease --stacktrace --console=plain
```

The release APK is expected at:

```text
android/app/build/outputs/apk/release/app-release.apk
```

## Python-free guard

The CI build performs a guard before Gradle compilation. Android changes must not introduce the Python runtime path or Chaquopy integration. Keep renderer/import/export work in Kotlin/native Android code.

## Iterating quickly

For local UI work you can use normal debug assembly/install tasks, then run the full unit-test + release-assembly command before submitting or publishing a build.

## Build verification

A production artifact should not be considered valid just because Gradle started. The repository workflow verifies that the expected release APK exists before uploading the artifact.

## GitHub Actions

The branch workflow builds on relevant Android/workflow pushes and can also be run manually. The workflow uploads an artifact named similar to `Cubical-Compare-2.0.7-Native-Fork` containing the verified APK and checksum information.

## Signing

Do not casually change application ID or signing identity on iterative builds. Android treats a changed signature/package combination as a different or incompatible install and users may need to uninstall the old package.

## Performance testing

Desktop CI can prove compilation/tests, but it cannot prove Android GPU/MediaCodec throughput. Renderer/export performance must be tested on real Android hardware, especially when changing codec selection, per-frame allocations, bitmap decoding or custom intro handling.
