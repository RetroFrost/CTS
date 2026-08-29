# Preview and Export Pipeline

Preview and export intentionally share renderer semantics so what the user edits is what the exported MP4 receives.

## Preview path

The Preview page requests a rendered frame at a reduced preview size, currently suitable for an interactive 16:9 viewport.

Conceptually:

```text
StudioProject
  ↓
RendererBridge.render(...)
  ↓
active renderer engine
  ↓
Bitmap
  ↓
Compose Image
```

The preview bitmap is recycled/replaced carefully to avoid retaining every rendered frame in memory.

## Direct transform preview

While transforming a selected card, the draft card is substituted into a temporary project and sent through the real renderer. This preserves:

- badge/artwork z-order;
- crop behaviour;
- renderer-specific card geometry;
- title/description layout interaction;
- the same frame semantics used by export.

Compose then overlays only editing affordances such as the selection bounds and handles.

## Renderer bridge

`RendererBridge` maps project-level concepts into renderer frames. Important responsibilities include:

- canonical renderer dispatch;
- intro-mode mapping;
- render metadata;
- import/materialisation helpers;
- renderer intro frame counts.

The renderer-default intro path should remain a direct/fast path rather than paying custom-video decode overhead on every frame.

## Custom MP4 intro

Custom intro playback has a separate decode path. Project frames inside the custom intro map to decoded MP4 frames. Once the intro finishes, project timing resumes at the renderer's canonical comparison start.

Developers must avoid doing expensive random-access frame extraction for every output frame when a sequential/batched decoder can be used.

## Export path

Conceptually:

```text
StudioProject
  ↓
RenderMetadata
  ↓
for each output frame
  RendererBridge / engine
  ↓
RGBA / render surface
  ↓
HardwareVideoExporter
  ↓
MediaCodec
  ↓
MP4 muxing + audio
```

`ExportService` owns lifecycle/progress/cancellation around this operation.

## Hardware codecs

The project can request Auto, H.264 or H.265. The codec selector resolves a compatible encoder for the requested size/FPS.

Do not assume an encoder that exists on one phone behaves the same on another; throughput, accepted colour formats and HEVC quality vary by SoC/vendor.

## Performance-sensitive areas

Changes here should be treated carefully:

- per-frame `ByteArray` allocations;
- texture upload strategy;
- bitmap decode size;
- custom intro decoding;
- synchronisation/locks in the hot render path;
- simultaneous preview rendering during export;
- unnecessary project recomposition/autosave work;
- CPU copies between renderer and encoder.

## Correctness before optimisation

Never optimise preview by drawing a visually similar but semantically different representation of the card. A preview shortcut is acceptable only if it preserves the transform/crop/layer result the export renderer would produce.

## Testing

CI can validate compilation and unit tests. Real performance and codec behaviour require device tests. For renderer correctness, verify representative exact frames against the source/reference material, not just a smooth-looking playback.
