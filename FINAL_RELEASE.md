# Cubical Compare 2.0.0 — Final

Cubical Compare 2.0.0 is the final Windows + Android application rebuild around one renderer.

## Renderer contract

The visual renderer is frozen from commit `a75020c120ac788ca10d57a113775e221e907a94`, after dense contact-sheet verification against the 1920×1080 60 fps reference. The final application rebuild does not modify `engine/ccengine`.

Android embeds a byte-for-byte copy of `engine/ccengine` under `android/app/src/main/python/ccengine` and calls it through a thin Chaquopy bridge. There is no second Kotlin animation implementation.

## Windows

The Windows application shell was rewritten from scratch as a native Win32 studio. It owns project editing, card management, file dialogs, timeline control, background tasks, progress and cancellation. Preview, spreadsheet import, MegaPack import and MP4 export are delegated to the private shared renderer engine.

## Android

The Android application shell was rewritten from scratch in Kotlin + Compose. It provides adaptive phone/tablet editing, project open/save, spreadsheet import, MegaPack import, artwork and soundtrack selection, exact frame preview, and foreground MP4 export which continues with the screen off and can be canceled from the app or notification.

Every Android video frame is produced by the same Python `FrameRenderer` used by the desktop renderer. Android's MediaCodec pipeline only encodes those rendered frames into H.264/MP4 and optionally encodes/muxes the soundtrack.

## Release gates

CI rejects the release if:

- `engine/ccengine` differs from the verified renderer baseline.
- the Android renderer copy differs byte-for-byte from `engine/ccengine`.
- any former Kotlin reference/timeline renderer returns.
- renderer regression tests fail.
- the Windows native shell or private renderer self-test fails.
- the Android release APK fails to assemble.
- stable Android signing is required but the configured release identity is unavailable or mismatched.
