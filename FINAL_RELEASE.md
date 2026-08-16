# Cubical Compare 2.0.1 — Final Hotfix

Cubical Compare 2.0.1 is the Android lifecycle and system-inset hotfix for the final Windows + Android rebuild.

Release channel: `release/cubical-compare-final`.

## 2.0.1 Android fixes

- Preview recomposition no longer reports `The coroutine scope left the composition` as a renderer failure. Superseded preview jobs now propagate coroutine cancellation normally instead of turning expected cancellation into an error message.
- The top action bar now respects the Android status bar inset, so status icons no longer overlap New, Open, Save, Data, or MegaPack.
- The bottom status/export bar now respects the navigation-bar inset instead of sitting underneath the system navigation controls.
- The default exported filename is updated to `Cubical-Compare-2.0.1.mp4`.

## Renderer contract

The visual renderer remains frozen from commit `a75020c120ac788ca10d57a113775e221e907a94`, after dense contact-sheet verification against the 1920×1080 60 fps reference. This hotfix does not modify `engine/ccengine`.

Android embeds a byte-for-byte copy of `engine/ccengine` under `android/app/src/main/python/ccengine` and calls it through the Chaquopy bridge. There is no second Kotlin animation implementation.

## Windows

The Windows application shell remains the rebuilt native Win32 studio from 2.0.0. It owns project editing, card management, file dialogs, timeline control, background tasks, progress and cancellation. Preview, spreadsheet import, MegaPack import and MP4 export are delegated to the private shared renderer engine.

## Android

The Android application shell is Kotlin + Compose and provides adaptive phone/tablet editing, project open/save, spreadsheet import, MegaPack import, artwork and soundtrack selection, exact frame preview, and foreground MP4 export which continues with the screen off and can be canceled from the app or notification.

Every Android video frame is produced by the same Python `FrameRenderer` used by the desktop renderer. Android's MediaCodec pipeline only encodes those rendered frames into H.264/MP4 and optionally encodes/muxes the soundtrack.

## Android signing

The release pipeline uses a permanent Android release identity whenever one is configured through private repository secrets and verifies its expected certificate fingerprint. This public repository currently contains no permanent private signing key. When those private secrets are absent, CI signs the APK with its local installable fallback identity instead of publishing an unsigned APK. The certificate SHA-256 fingerprint and signing mode are shipped beside the APK as `Cubical-Compare-2.0.1-Android.signing.txt`; no private key is committed or published.

## Release gates

CI rejects the release if:

- `engine/ccengine` differs from the verified renderer baseline.
- the Android renderer copy differs byte-for-byte from `engine/ccengine`.
- any former Kotlin reference/timeline renderer returns.
- renderer regression tests fail.
- the Windows native shell or private renderer self-test fails.
- the Android release APK fails to assemble.
- the Android APK is not cryptographically signed.
- a configured permanent Android release identity has the wrong certificate fingerprint.
