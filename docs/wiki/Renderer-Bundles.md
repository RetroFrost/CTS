# Renderer Bundles

Renderer bundles are declarative `.renderer` packages that tell Cubical Compare how a comparison model should look and move.

## Why renderers are separate

The editor owns project data and export orchestration. A renderer owns model-specific geometry, timing, animation tracks and composition rules. This allows Cubical Compare to support multiple comparison styles without hard-coding every model into the UI.

## Renderer API version

Current native bundles target **Renderer API 2**.

## Precision modes

Renderers may be adaptive or frame-exact. A frame-exact renderer can declare canonical resolution, frame rate, card count and timing information. Cubical Compare reports when a project still matches those canonical settings and when user edits make it a modified render.

## Engines

The native renderer runtime currently supports engine families such as:

- `native-standard`;
- `ribbon-exact`;
- `relationships-exact`.

The engine determines how declarative bundle data is interpreted; the `.renderer` file itself does not execute arbitrary code.

## Installing

Open **More → Renderer** to import a bundle, then use **Renderer library** to inspect installed renderers.

## Exact-renderer expectations

A renderer marked frame-exact should describe the source animation rather than approximate it with generic transitions. Timing, motion curves, badge behaviour, card spacing and entry/exit geometry belong in the renderer data or engine implementation.

## Intro handling

Renderer bundles can own a canonical intro. Projects may keep it, replace it with a custom MP4, or remove it. Removing the intro shifts the project directly to comparison frames; it does not substitute black frames.

For the full developer-facing bundle contract, see [Renderer API v2](Renderer-API-v2.md).
