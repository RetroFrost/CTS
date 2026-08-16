# Cubical Compare 2.0.2 — Final Hotfix

Cubical Compare 2.0.2 fixes Android MegaPack memory crashes and makes long exports persist as true background media-processing jobs.

Release channel: `release/cubical-compare-final`.

## 2.0.2 Android fixes

- MegaPack import no longer runs on the Compose/activity thread.
- Android now processes MegaPack artwork one card at a time instead of retaining every referenced compressed image in memory. The 44-card `CTS_MegaPack_Most_Improper_Liquids_Felix.zip` expands to roughly 264 MB of RGBA source pixels, so the previous import strategy could exceed a phone process heap.
- Partial MegaPack destinations are removed after a failed import, and the source cache copy is deleted when import finishes.
- The editor shows an indeterminate import progress state and disables duplicate MegaPack/import-dependent export actions while loading.
- Export runs in a persistent `mediaProcessing` foreground service with a partial wake lock, notification progress and cancel action.
- The export destination URI is persisted when the document provider permits it, and the active project/destination request is stored so Android can recreate the service and redeliver the export after process/service interruption.
- Swiping the editor task away no longer stops the export service. Screen-off export remains supported.
- Completed, canceled and failed exports now leave a final notification state instead of an orphaned in-progress notification.
- Default exported filename is `Cubical-Compare-2.0.2.mp4`.

## Renderer contract

The visual renderer remains frozen from commit `a75020c120ac788ca10d57a113775e221e907a94`, after dense contact-sheet verification against the 1920×1080 60 fps reference. This hotfix does not modify `engine/ccengine`.

Android still embeds a byte-for-byte copy of `engine/ccengine` under `android/app/src/main/python/ccengine`. The new memory-bounded MegaPack logic is only in the Android bridge around that renderer; there is still no second Kotlin animation implementation.

## Windows

The Windows application and renderer are unchanged functionally from the reviewed final rebuild. CI rebuilds and re-verifies Windows for this release so cross-platform renderer freeze checks remain enforced.

## Android signing

The release pipeline uses a permanent Android release identity whenever one is configured through private repository secrets and verifies its expected certificate fingerprint. When those private secrets are absent, CI signs the APK with its installable fallback identity. The certificate SHA-256 fingerprint and signing mode are shipped beside the APK as `Cubical-Compare-2.0.2-Android.signing.txt`; no private key is committed or published.

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
