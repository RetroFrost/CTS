# Cubical Compare 2.0.8 — Exact Renderer Release

Release channel: `fork/2.0.7-renderer-bundles`.

## Highlights

### Importable `.renderer` bundles

- Cubical Compare can import declarative `.renderer` bundles directly from Android's document picker, file intents and Share sheet.
- Renderer bundles are inspected before activation and report compatibility, warnings and diagnostics.
- Renderers remain project-reusable: source-exact motion data does not hard-lock a bundle to one card count, duration, frame rate or output size.

### Source-exact animation engine

- Frame-exact renderer tracks now bypass temporal smoothing instead of being altered after import.
- Opening cards can use measured per-frame clip tracks and source-addressed motion rather than generic slide interpolation.
- Opening and later badges can carry their own measured position, scale, text, blur and shine tracks.
- Badge deemphasis now follows measured source geometry instead of the old generic scale-down timing.
- Continuous scrolling accepts additional measured track segments instead of falling back after the first few segments.
- Preview and export continue to resolve the same renderer/timeline semantics.

### Evolution of Language fidelity work

- Added renderer-engine support needed by the measured `Evolution of Language` renderer.
- Corrected the opening background fade, badge visibility timing, badge text timing and source-frame motion handling.
- Corrected final-card ownership during the outro so short-project logic does not substitute the credits panel for the actual final card.
- Added measured outro/background hooks while preserving reusable fallback behaviour for other projects.

### Interface and workflow

- Renderer importing and management use the current Material 3 interface and motion behaviour.
- The app label and Android package version are now Cubical Compare 2.0.8.
- Android version code is advanced so this build upgrades the recent 2.0.7 native-fork builds rather than installing as an older package revision.

## Release gates

The 2.0.8 Android release is published only after:

- Android unit tests pass;
- the release APK assembles successfully;
- the resulting APK is verified with `apksigner`;
- the configured permanent signing certificate matches the expected fingerprint when release signing secrets are available; and
- a SHA-256 checksum and signing report are generated beside the APK.
