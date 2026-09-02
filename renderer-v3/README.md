# CTS Renderer API v3 compiler

`tools/renderer_v3_compiler.py` is a dependency-free compiler/validator for the proposed source-exact Renderer API v3 scene format.

It deliberately emits `.renderer3`, not the existing Android `.renderer` v1/v2 format. That prevents a v3 scene from being imported by an app build whose runtime cannot execute the v3 scene graph yet.

## What v3 source can express

The IR carries the full source-exact checklist: per-frame polygon vertices, arbitrary 2D transforms and matrices, renderer-owned shine geometry, masks, raw/hold/linear/cubic tracks, independent objects, text tracks, layers, shadows, dense frame arrays, frame-addressed objects, selectors, property-level selector inheritance, zero implicit animation, generic resources/groups, lifespans, reusable selector behavior, geometry/materials/blends/filters/artwork transforms, absolute frame clock, locked reference FPS/resolution, checkpoints, pixel-diff audit metadata, cascade inspection, and a single scene-evaluator contract for preview/export.

## Frame-addressed objects

An object has an anchor frame:

```json
{"id":"badge@528","kind":"badge","frame":528,"resource":"badgeGroup"}
```

With no movement/shine/etc. property declared, the compiler invents nothing. The object is static.

## Selectors

Supported selector forms include:

```text
badge[*]
badge[frame=120]
badge[frame>=528]
badge[frame=528..1600]
badge[every=214,from=528]
badge[every=214,from=528,to=12492]
```

Selectors merge **per property**. More specific selectors win; an object's own property declaration always wins over selectors. Equal selector specificity is resolved by source order (later declaration wins).

## Relative vs absolute tracks

A selector normally uses `"timeline":"relative"`; frame 0 means the selected object's anchor frame.

```json
{
  "select":"badge[every=214,from=528]",
  "timeline":"relative",
  "properties": {
    "movement": {
      "y": {
        "track":[[122,-430],[206,0]],
        "interpolation":"linear",
        "extrapolate":"hold"
      }
    }
  }
}
```

Use `"timeline":"absolute"` when keys are global video frames.

## Dense RAW tracks

For frame measurements, use dense raw data:

```json
{
  "shine": {
    "topX": {
      "dense":{"start":208,"values":[130,152,178,207]},
      "timeline":"relative",
      "interpolation":"raw",
      "extrapolate":"none"
    }
  }
}
```

`raw` is not smoothed or eased.

## Commands

```bash
python3 tools/renderer_v3_compiler.py selftest
python3 tools/renderer_v3_compiler.py lint renderer-v3/examples/watchdata-hand-dissolve.json
python3 tools/renderer_v3_compiler.py compile renderer-v3/examples/watchdata-hand-dissolve.json -o dist/watchdata.renderer3
python3 tools/renderer_v3_compiler.py inspect dist/watchdata.renderer3
python3 tools/renderer_v3_compiler.py explain renderer-v3/examples/watchdata-hand-dissolve.json --object badge@528 --frame 650
python3 tools/renderer_v3_compiler.py dump-ir renderer-v3/examples/watchdata-hand-dissolve.json
```

## Binary container

`.renderer3` uses an intentionally separate deterministic container:

```text
8 bytes  magic: CCRNDR03
4 bytes  big-endian container version
4 bytes  big-endian gzip payload length
4 bytes  big-endian CRC32 of gzip payload
N bytes  deterministic gzip of canonical Renderer API v3 Scene IR JSON
```

The gzip timestamp is zeroed so identical source compiles reproducibly.

## GitHub Actions

The `Renderer v3 compiler` workflow runs the self-test, lints the selected source, compiles it, inspects it, exercises selector-cascade explanation, and uploads the `.renderer3` output as an artifact.

The current Android app still needs the Renderer API v3 scene runtime before `.renderer3` files can be imported/rendered directly. The compiler is intentionally useful before that runtime exists: it gives us a stable format to generate exact WatchData measurements against.
