# Developer FAQ

## Why is the Android app Python-free?

The native fork avoids shipping a Python runtime or Chaquopy integration. This keeps renderer/export code in Kotlin/native Android paths and avoids a second runtime inside the APK.

## Where should a renderer-specific animation go?

Prefer renderer bundle data or the renderer engine. Generic UI should not hard-code one video's motion.

## Why does the transform editor use a draft card?

Touch events can arrive many times per second. Writing the entire `StudioProject`, recalculating metadata and scheduling autosave on each event causes unnecessary recomposition and memory/CPU pressure. Draft state lets the UI update continuously and commit once.

## Can preview draw a separate image overlay for speed?

Only if the result is guaranteed to match the renderer. The direct transformer instead redraws the draft through the real renderer so crop and badge layering stay truthful.

## How do I add a new project field?

Add the field with a safe default, update JSON serialisation/parsing, consider migration/backwards compatibility, and decide whether the field affects render metadata or only visual composition.

## How do I add a new renderer engine?

Implement a deterministic native engine, expose only declarative bundle configuration, integrate it with renderer dispatch, test representative frames and document the engine in Renderer API v2.

## What should I profile when export FPS drops?

Start with changes to the per-frame hot path: allocations, image decode, intro decode, renderer locks, texture uploads, preview/export concurrency and MediaCodec submission.

## Does a green CI build prove a renderer is pixel-exact?

No. CI proves tests/build/artifact verification. Pixel/frame exactness requires reference-frame comparison against the source video.
