# Exporting Video

## Start an export

Open **More → Export** and press **Export video**. Android's document picker asks where the MP4 should be saved, so Cubical Compare does not force one fixed output folder.

## Encoder selection

Project encoder choices are:

- **Auto** — Cubical Compare chooses a suitable hardware codec;
- **H.264** — broad compatibility;
- **H.265** — potentially better compression and/or performance on supported hardware.

The exact codec offered depends on the device and requested resolution/frame rate.

## Hardware path

The native fork uses Android hardware/media APIs rather than Python or FFmpeg as its normal Android render/export runtime. The export service keeps progress state and can continue while the editor UI is not actively foregrounded, subject to Android process/device restrictions.

## Progress

The Export card reports the current stage, detail and completion percentage. Use **Cancel export** to stop an active job.

## Soundtrack

When a soundtrack is configured, Cubical Compare applies the chosen volume and loop preference during the output pipeline.

## Custom intro

A custom MP4 replaces the canonical renderer intro. The canonical renderer path remains the preferred fast path when no custom video intro is requested.

## Performance tips

- Keep Preview paused while benchmarking export performance.
- Use Auto first unless a specific codec is required.
- Very large artwork can increase decode/memory pressure even when preview uses sampled copies.
- Export performance is device- and codec-dependent; a high preview frame rate does not guarantee identical encode throughput.

## Failed exports

If export fails, note the stage shown in the Export card and check [Troubleshooting](Troubleshooting.md). If the app terminates unexpectedly, use the crash information available from the build/app when possible.
