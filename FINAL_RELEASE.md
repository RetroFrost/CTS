# Cubical Compare 2.0.6 — Thumbnail & MegaPack Release

Release channel: `release/cubical-compare-final`.

## Features

### Automatic thumbnails

- A successful export creates three 1280×720 JPEG thumbnails beside the MP4.
- Filenames follow the video basename: `Video - Thumbnail 1.jpg`, `Video - Thumbnail 2.jpg`, and `Video - Thumbnail 3.jpg`.
- Thumbnails use representative frames around 16%, 48% and 78% of the comparison timeline.
- Android composes WatchData-inspired thumbnails from the exact shared renderer frame, with a high-contrast dark scrim, short headline, red value callout and bundled Poppins Bold typography.
- Android exports to a selected folder so the MP4 and JPG files can reliably be written as siblings through the Storage Access Framework.
- Android thumbnail creation remains part of the foreground background-export service, including progress, screen-off support and cancellation.
- Windows writes three renderer-based 1280×720 JPG stills directly beside the exported MP4 after a successful render.
- A thumbnail failure does not invalidate a successfully completed MP4; Windows reports it as a thumbnail warning.

### British-English Felix MegaPack

The companion pack is `CTS_MegaPack_Most_Improper_Liquids_Felix_UK.zip`.

- 44 cards.
- User-facing wording is British English, including terms such as `petrol`, `windscreen washer fluid`, `washing-up liquid`, `hand sanitiser`, `oesophagus` and `labelled` where applicable.
- The comparison model is the exact WatchData-style reference model with badges enabled.
- Rank values are preserved as the card badge values.
- Each artwork uses a full-card background treatment and keeps Felix beneath the badge-safe region.
- The pack is ZIP CRC-tested and every PNG artwork is decoded and verified after rebuilding.

### Cross-platform release pipeline

- Windows x64 and Android are built from the same shared renderer contract.
- Successful builds publish downloadable GitHub release assets automatically.
- Android uses a permanent release identity when configured through private repository secrets, otherwise CI uses its installable fallback identity and publishes the certificate SHA-256 fingerprint beside the APK.

## Bug fixes

- Fixed the badge shine so the streak physically sweeps through and exits the lower-right edge of the hexagon before disappearing, instead of fading away while part of the highlight is still on the badge.
- Fixed long card titles and descriptions so text wraps across additional readable lines instead of becoming unreadably small or overflowing the card.
- Kept the Android embedded renderer byte-for-byte synchronised with the desktop renderer so cross-platform release validation passes.
- Corrected the renderer checkpoint used by the release workflow so the reviewed renderer fixes pass the frozen-renderer contract.
- Added workflow diagnostics that expose successful push runs and make Actions/artifact verification reliable.
- Retained Play and Stop preview controls, real-time preview playback, full-frame final export, background exporting, progress reporting, cancellation, screen-off support and service recreation support.

## Renderer contract

The 2.0.6 release keeps the reviewed WatchData-style renderer as the release checkpoint. CI rejects a build if Android and desktop renderer trees diverge or if the shared renderer changes outside the reviewed checkpoint.

## Release gates

CI rejects 2.0.6 if:

- Android and desktop renderer source trees diverge;
- renderer regression tests fail;
- thumbnail/export contract tests fail;
- the Windows private renderer or native-shell self-test fails;
- Android unit tests or release assembly fail;
- the Android APK is not cryptographically signed; or
- a configured permanent Android release identity has the wrong certificate fingerprint.
