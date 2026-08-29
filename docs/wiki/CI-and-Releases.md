# CI and Releases

Cubical Compare's Android CI is designed to prove that the native fork still builds, remains Python-free and produces a real release APK before an artifact is published.

## Main Android workflow

The native 2.0.7 workflow lives under `.github/workflows/` and builds the active renderer-bundle branch.

The important build command is:

```bash
android/gradlew -p android :app:testDebugUnitTest :app:assembleRelease --stacktrace --console=plain
```

This runs debug unit tests and assembles the release APK in the same workflow job.

## Python-free check

Before Gradle compilation, CI verifies that the Android runtime has not reintroduced Python integration. Keep this guard intact when editing the workflow.

## APK verification

The workflow expects:

```text
android/app/build/outputs/apk/release/app-release.apk
```

A workflow should fail if that file does not exist. A green compile step without a verified APK is not sufficient for publishing.

## Artifact

The verified APK is copied into a stable artifact name and uploaded with a checksum. Artifacts are retained for a limited period, so permanent public builds should eventually be attached to an appropriate release.

## Iteration vs full validation

For fast development, local debug builds are fine. For a build given to users, run the full workflow including tests, release assembly and APK verification.

If CI becomes too slow for every source push, prefer separate intentional fast/full workflows rather than silently deleting validation from the release workflow.

## Renderer changes

A green Android build only proves code validity. For exact renderer changes also verify:

- canonical frame count;
- representative animation boundaries;
- card positions and spacing;
- badge timing/layering;
- intro/ending behaviour;
- device export performance when the hot path changed.

## Release checklist

Before marking a build as usable:

1. workflow is green;
2. unit tests passed;
3. Python-free guard passed;
4. release APK exists and was verified;
5. artifact upload succeeded;
6. checksum recorded;
7. important UI path smoke-tested on Android;
8. export tested on real hardware when renderer/media code changed.

## Versioning

Keep app version name/code, changelog and artifact naming aligned. Renderer bundles have their own stable IDs/API compatibility and should not rely solely on the app version string for compatibility decisions.
