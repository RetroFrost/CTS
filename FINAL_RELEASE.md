# Cubical Compare 2.0.7 — GPU, MegaPack & Material You Release

Release channel: `release/cubical-compare-final`.

## Features

### Focused Android workspace

- Project and import actions now live in a dedicated Tools tab.
- A new FAQ tab explains projects, MegaPacks, video length, background export, encoders, dynamic colours and badge fields.
- Material You dynamic colours follow the device wallpaper on Android 12 and newer, with polished light and dark fallback schemes.
- A new Cubical Compare launcher icon is included.

### GPU video export

- Android exports the finished MP4 with a hardware GPU/MediaCodec pipeline.
- Auto mode prefers hardware H.265 and falls back to hardware H.264; either codec can be selected directly.
- Auto also retries with H.264 if the selected H.265 encoder fails while initialising or rendering.
- Export continues through a foreground service with progress, cancellation and screen-off support.
- Thumbnail generation has been removed: export now creates only the requested MP4.

### Badge and MegaPack fidelity

- Badge text supports a dedicated header, main value and unit layout.
- Later badges keep a fixed size instead of growing to highlight themselves; their vertical fall animation remains intact.
- An optional toggle places post-opening badges directly at their final position throughout continuous scrolling.
- MegaPack cards import badge headers and the manifest video duration.
- Custom video length changes continuous-scroll speed without changing the comparison content.

### Cross-platform release pipeline

- Windows x64 and Android are built from the same shared renderer contract.
- Successful builds publish downloadable GitHub release assets automatically.
- Android uses a permanent release identity when configured through private repository secrets, otherwise CI uses its installable fallback identity and publishes the certificate SHA-256 fingerprint beside the APK.

## Bug fixes

- Corrected three-line badge positioning so the header no longer drifts away from the value and unit.
- Restored the original visual size of three-line badge text.
- Locked both the opening sequence and later conveyor to one consistently large badge size, so badges no longer appear randomly small or grow to highlight themselves.
- Kept the Android embedded renderer byte-for-byte synchronised with the desktop renderer.
- Retained the vertical badge fall, preview playback and full-frame final export.
- Hardened Android export against preview-memory pressure, encoder-specific bitrate modes and EGL configuration differences.

## Renderer contract

The 2.0.7 release updates the reviewed WatchData-style renderer for the corrected badge text and fixed badge scale. CI rejects a build if Android and desktop renderer trees diverge.

## Release gates

CI rejects 2.0.7 if:

- Android and desktop renderer source trees diverge;
- renderer regression tests fail;
- renderer or export contract tests fail;
- the Windows private renderer or native-shell self-test fails;
- Android unit tests or release assembly fail;
- the Android APK is not cryptographically signed; or
- a configured permanent Android release identity has the wrong certificate fingerprint.
