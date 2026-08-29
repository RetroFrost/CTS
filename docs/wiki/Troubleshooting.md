# Troubleshooting

## APK will not install

- Confirm the file is an APK, not the GitHub Actions ZIP.
- Re-download the artifact if Android reports the package is invalid or cannot be parsed.
- If Android blocks the source app, allow installation from that source and retry.
- If a build changes package/signing identity, uninstalling the old build may be required. Back up portable project files first.

## Preview cannot select artwork

Selection only works when a card with an artwork image is visible at the current renderer frame. Scrub to a frame where the target card is clearly on screen and tap its artwork region.

## Transform feels wrong

The current direct transformer renders the draft through the real renderer. If the selection box and exported image disagree, record the exact card/frame and active renderer because that indicates a coordinate mapping bug rather than a cosmetic preview issue.

## Badge text overflows

Recent native Relationships rendering measures large badge values and fits them to the available badge width. If overflow occurs, include the value string, renderer ID and screenshot/frame.

## Artwork appears in front of the badge

Check the card's layer. Normal MegaPack artwork should use `behind`. The preview/export renderer should then draw the badge above the artwork.

## Export is unexpectedly slow

- Pause Preview before testing export speed.
- Use the renderer-default intro when comparing against canonical performance; custom MP4 decoding has additional work.
- Try Auto, H.264 and H.265 to identify codec-specific performance.
- Keep enough storage free for the MP4 and encoder buffers.
- Large source artwork can increase decode pressure.

## App crashes during artwork editing

Use sampled artwork rather than decoding full-resolution images into the editor. Current transform gestures remain local until Done specifically to avoid project/autosave churn on every touch event.

If a crash persists, capture the exact action sequence, renderer, project/card count and any crash report text.

## MegaPack imports but images are missing

Verify the ZIP actually contains the referenced files and that paths are relative to the pack rather than machine-specific absolute paths.

## Renderer will not import

Check that the bundle declares a supported renderer API and valid engine. Renderer bundles are declarative; unsupported executable/plugin payloads are not accepted as renderer code.
