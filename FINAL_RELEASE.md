# Cubical Compare 2.0.5 — Thumbnail & MegaPack Release

Cubical Compare 2.0.5 keeps the checkpointed 2.0.4 WatchData video renderer unchanged and adds post-export YouTube thumbnail generation plus the rebuilt British-English Felix MegaPack workflow.

Release channel: `release/cubical-compare-final`.
2.0.4 checkpoint: `d57817542baadd074028e4c0549660f9979b5972`.
Renderer visual-audit baseline: `56824d34ab99c37bf9a7869a3b15ab57809a2e74`.

## Automatic thumbnails

- A successful export now creates three 1280×720 JPEG thumbnails beside the MP4.
- Filenames follow the video basename: `Video - Thumbnail 1.jpg`, `Video - Thumbnail 2.jpg`, and `Video - Thumbnail 3.jpg`.
- Thumbnails use representative frames around 16%, 48% and 78% of the comparison timeline.
- Android composes WatchData-inspired thumbnails from the exact shared renderer frame, with a high-contrast dark scrim, short headline, red value callout and bundled Poppins Bold typography.
- Android exports to a selected folder so the MP4 and JPG files can reliably be written as siblings through the Storage Access Framework.
- Android thumbnail creation remains part of the foreground background-export service, including progress, screen-off support and cancellation.
- Windows writes three renderer-based 1280×720 JPG stills directly beside the exported MP4 after a successful render.
- A thumbnail failure does not invalidate a successfully completed MP4; Windows reports it as a thumbnail warning.

## Renderer checkpoint contract

2.0.5 does not modify the comparison animation renderer. CI rejects the build if `engine/ccengine` differs from the reviewed 2.0.4 renderer baseline or if Android's embedded renderer differs from the desktop renderer tree.

The 2.0.4 WatchData corrections therefore remain intact, including measured badge geometry and opening motion, corrected shine lifetime, authored title line breaks, measured text wrapping and the bundled Poppins renderer typography.

## British-English Felix MegaPack

The companion pack is `CTS_MegaPack_Most_Improper_Liquids_Felix_UK.zip`.

- 44 cards.
- User-facing wording is British English, including terms such as `petrol`, `windscreen washer fluid`, `washing-up liquid`, `hand sanitiser`, `oesophagus` and `labelled` where applicable.
- The comparison model is the exact WatchData-style reference model with badges enabled.
- Rank values are preserved as the card badge values.
- Each artwork was rebuilt with a full-card background treatment and Felix shifted lower so his face/eyes remain beneath the badge-safe region instead of being obscured by the badge.
- The pack was ZIP CRC-tested and every PNG artwork was decoded and verified after rebuilding.

## Android fixes retained

- Play and Stop preview controls remain available.
- Preview playback remains clocked to real video time; final export still renders every output frame.
- MegaPack import remains off the activity/Compose thread and processes artwork one card at a time.
- Background export remains a persistent `mediaProcessing` foreground service with wake lock, notification progress, cancellation, screen-off support and service recreation support.

## Android signing

The release pipeline uses a permanent Android release identity whenever one is configured through private repository secrets and verifies its expected certificate fingerprint. When those secrets are absent, CI signs the APK with its installable fallback identity. The certificate SHA-256 fingerprint and signing mode are shipped beside the APK; no private key is committed or published.

## Release gates

CI rejects 2.0.5 if:

- the checkpointed WatchData renderer changes;
- Android and desktop renderer source trees diverge;
- renderer regression tests fail;
- the thumbnail/export contract tests fail;
- the Windows private renderer or native-shell self-test fails;
- Android unit tests or release assembly fail;
- the Android APK is not cryptographically signed; or
- a configured permanent Android release identity has the wrong certificate fingerprint.
