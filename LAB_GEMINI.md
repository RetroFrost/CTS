# Cubical Compare Lab — Gemini Reference Experiment

This is an experimental build derived from Cubical Compare 2.0.5. It is intentionally isolated from `release/cubical-compare-final` and does not replace the reviewed 2.0.5 renderer.

## What this Lab actually changes

- **Full-frame scaling:** non-16:9 output stretches the complete 1920×1080 composition to the selected viewport instead of adding black pillar/letter bars. This preserves the card field and right-side credits, but can distort geometry on unusual aspect ratios.
- **Integer-locked carousel transforms:** card X coordinates are rounded once before body, badge and foreground layers are drawn, testing Gemini's suggestion that common pixel anchoring may reduce perceived micro-wobble.
- **Wider off-screen buffer:** two full card pitches are kept eligible on either side of the viewport so incoming artwork is already composed before it becomes visible.
- **Stronger badge depth:** badges use a thick white keyline and a stronger fixed directional drop shadow.
- **Centre-focus badge pop:** badges can reach 1.08× scale while crossing the centre 30% of the canvas, then return to their normal scale.
- **Banner separation:** title and description bands receive small highlight/shadow separators while keeping the existing Poppins typography and text fitting.
- **Damped final settle:** the final conveyor section is re-timed through a normalised exponential ease-out to test a softer stop before the end-screen sequence.
- **Fixed 60 FPS Windows Lab export:** the Lab CLI forces the project export tick to 60 FPS. Android's existing encoder is already frame-indexed from the project FPS and the reference project defaults to 60 FPS.

## Things Gemini criticised which 2.0.5 already did

The stable exporter already renders deterministic frame-number / FPS timestamps rather than screen-capturing a real-time canvas. The stable renderer also already keeps an off-screen card buffer and uses measured source-frame motion. This Lab extends those behaviours rather than duplicating them.

## Deliberate reference deviations

The 1.08× centre pop, thicker white badge outline, full-frame aspect stretching and exponential final settle are not claimed to be measurements from the supplied `Timeline of Language` reference. They are visual experiments requested specifically for the Lab build.

If these changes look better in real exported footage, they can be audited individually against contact sheets before any selected behaviour is promoted to the stable renderer.
