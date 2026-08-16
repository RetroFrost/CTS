# Cubical Compare 2.0.3 — Final Hotfix

Cubical Compare 2.0.3 adds explicit Play and Stop controls to the Android renderer preview while preserving the 2.0.2 MegaPack and background-export fixes.

Release channel: `release/cubical-compare-final`.

## 2.0.3 Android changes

- The preview now has labeled **Play** and **Stop** buttons directly under the timeline slider.
- Play begins from the current playhead. If the playhead is already on the final frame, playback restarts from frame zero.
- Stop halts playback immediately and leaves the playhead on the currently displayed frame.
- Dragging the timeline slider automatically stops playback and switches back to exact single-frame preview rendering.
- Opening/importing a project, importing a MegaPack, creating a new project, editing the card set or starting export stops preview playback cleanly.
- Preview playback is clocked from the project's real FPS. If a phone cannot render every 960x540 preview frame in real time, intermediate preview frames are skipped so playback timing stays correct instead of running in slow motion.
- This preview-only frame skipping does **not** affect export. Export still renders and encodes every frame at the locked 60 FPS reference cadence.
- Default exported filename is `Cubical-Compare-2.0.3.mp4`.

## 2.0.2 fixes retained

- MegaPack import remains off the Compose/activity thread and processes artwork one card at a time to avoid the large-memory crash seen with the 44-card liquids MegaPack.
- Export remains a persistent `mediaProcessing` foreground service with wake lock, notification progress, cancellation, screen-off support and service recreation support.

## Renderer contract

The visual renderer remains frozen from commit `a75020c120ac788ca10d57a113775e221e907a94`, after dense contact-sheet verification against the 1920x1080 60 FPS reference. This hotfix does not modify `engine/ccengine`.

Android still embeds a byte-for-byte copy of `engine/ccengine` under `android/app/src/main/python/ccengine`. Playback controls only schedule which exact renderer frame is requested for the live preview.

## Windows

The Windows application and renderer are unchanged functionally. CI rebuilds and re-verifies Windows so the cross-platform renderer freeze remains enforced.

## Android signing

The release pipeline uses a permanent Android release identity whenever one is configured through private repository secrets and verifies its expected certificate fingerprint. When those private secrets are absent, CI signs the APK with its installable fallback identity. The certificate SHA-256 fingerprint and signing mode are shipped beside the APK as `Cubical-Compare-2.0.3-Android.signing.txt`; no private key is committed or published.

## Release gates

CI rejects the release if:

- `engine/ccengine` differs from the verified renderer baseline.
- the Android renderer copy differs byte-for-byte from `engine/ccengine`.
- the Android Python bridge does not compile.
- any former Kotlin reference/timeline renderer returns.
- renderer regression tests fail.
- the Windows native shell or private renderer self-test fails.
- Android unit tests or release assembly fail.
- the Android APK is not cryptographically signed.
- a configured permanent Android release identity has the wrong certificate fingerprint.
