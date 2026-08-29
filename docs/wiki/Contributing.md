# Contributing

Contributions should preserve Cubical Compare's native, renderer-driven architecture rather than adding parallel approximation paths.

## Before coding

- Read [Developer Overview](Developer-Overview.md) and [Architecture](Architecture.md).
- Identify whether the change belongs in generic UI, project model, renderer bundle data, a renderer engine, importer or export/media code.
- For exact renderer work, inspect the reference material frame-by-frame before inventing animation behaviour.

## Branch/workflow

Work from the current development branch for the feature line you are targeting. Keep commits focused and avoid unrelated rewrites in the same change.

## Android rules

- Do not add a Python runtime or Chaquopy to the Android app.
- Prefer Kotlin/native Android APIs.
- Keep Compose state updates cheap during high-frequency gestures.
- Do not decode full-resolution artwork into editor UI unless absolutely necessary.
- Recycle/release bitmap/media resources predictably.

## Renderer rules

- Renderer bundles are declarative.
- Do not hide arbitrary executable code in bundles.
- Exact renderers should be deterministic for a given project/frame.
- Do not substitute generic bounce/pop/slide effects for source animation.
- Keep preview/export semantics identical.

## UI rules

- Keep the main editor understandable for non-developers.
- Prefer direct manipulation for visual operations such as artwork transform.
- Advanced tuning controls can exist, but should not dominate the normal path.
- Long text must fit its intended renderer region rather than overflow silently.

## MegaPack rules

- Keep packs self-contained and portable.
- Artwork normally fills the artwork region and remains behind the badge.
- Characters/objects are part of that artwork composition.
- Use consistent Lineal Color icon artwork when the pack is icon-based unless another style is explicitly intended.

## Testing

At minimum run:

```bash
android/gradlew -p android :app:testDebugUnitTest :app:assembleRelease --stacktrace --console=plain
```

Then test the modified behaviour on Android if the change affects gestures, rendering, MediaCodec, storage/document pickers or performance.

## Pull request notes

Explain:

- what user/developer problem is fixed;
- which subsystem changed;
- compatibility impact;
- renderer/project schema changes;
- how you verified the result;
- device/codec used for performance claims.

## Avoid

- placeholder implementations presented as finished features;
- hard-coded renderer-specific geometry in generic UI when renderer metadata can provide it;
- per-pointer-event full project persistence;
- claiming exactness without reference-frame verification;
- claiming performance from desktop CI alone.
