# Cubical Compare 1.0 Official Models

Cubical Compare models are immutable reproduction contracts, not editable animation presets.

The application currently ships with two official models:

- `what-males-learn-at-each-age`
- `types-of-relationships`

Each model directory contains a `model.json` manifest identifying its canonical reference video, exact SHA-256, resolution, frame rate, frame count, scene order, editable content fields, and immutable mechanics.

## Source of truth

The supplied reference videos are the source of truth for:

- scene order
- frame timing
- card positions
- card body movement
- badge deformation, fall, rebound, scale and ageing
- text reveal and motion trail
- shine timing
- credits-panel movement
- continuous conveyor movement
- outro wipe, rise, hold and fade
- output resolution and frame rate

A model implementation is not complete merely because it looks similar. It must be checked at the same source-frame indices against the canonical video.

The reference videos themselves are not stored in this repository. Their cryptographic identities are stored in the manifests and `ccengine.model_registry`.

## Editable content

Users may replace the data carried by a model:

- project name
- card order
- title
- value
- description
- image
- image transform inside the fixed artwork slot
- soundtrack
- credits text
- end-screen text

Content changes must not alter the model timeline or layout.

## Locked mechanics

Projects cannot override:

- 1920×1080 output geometry
- 60 FPS frame clock
- model revision
- cadence or total animation count
- animation curves
- layout geometry
- typography metrics
- credits-panel movement
- outro sequence

Legacy project values for resolution, FPS, automatic/custom duration or model revision are normalised back to the selected official model.

## Reference analysis

Generate a deterministic reference fingerprint with:

```text
python tools/analyze_reference_model.py <reference.mp4> \
  --model-id <model-id> \
  --output reference-analysis.json \
  --stride 30 \
  --detect-first-content \
  --expect-sha256 <sha256-from-model.json>
```

The report records exact video metadata, source hash, sampled-frame hashes, luma values and strongest frame changes. It is intended to support frame-by-frame conformance work and regression tests.

## Completion rule

A model may be marked complete only after:

1. Every scene in `sequence` is implemented.
2. Preview and export address the same integer frame clock.
3. No user control can add, remove, stretch or reorder model animation.
4. Golden frame comparisons cover every transition boundary and representative motion interval.
5. A full reference-length render passes the conformance thresholds defined for that model revision.
