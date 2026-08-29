# Renderer API v2

Renderer API v2 is Cubical Compare's declarative renderer contract for native Android rendering.

## Design goals

- Renderer bundles contain data, not arbitrary executable code.
- A renderer can describe canonical geometry, timing and animation tracks.
- Exact renderers can preserve source-video timing instead of relying on generic transitions.
- Preview and export consume the same renderer semantics.

## Identity

A renderer should have a stable ID and human-readable name. IDs are used by the renderer library/runtime and should not be changed casually once projects depend on them.

## Canonical properties

A frame-exact renderer can declare canonical values such as:

- reference width and height;
- reference FPS;
- canonical card count;
- canonical intro/timeline boundaries;
- slot/card geometry;
- engine identifier;
- precision mode.

The editor uses these values to report whether a project remains canonical or has been modified.

## Engines

The bundle selects a supported native engine. Current engine families include `native-standard`, `ribbon-exact` and `relationships-exact`.

The engine is implemented by the app. The bundle supplies declarative configuration/tracks/assets consumed by that engine.

## Tracks

Exact renderers can expose named animation tracks. A track maps a frame to a value such as scroll offset, opacity, position or another engine-specific quantity.

Track names should be deterministic and model-scoped. Example concepts include segmented scroll tracks such as `relationships.scroll.<segment>`.

## Card geometry

Renderer specs may define values such as slot pitch, body inset, body width, image height and title height. UI code that needs hit-testing should derive from renderer geometry rather than inventing independent card bounds.

## Timeline

A renderer owns canonical animation timing: opening entries, continuous scrolling, transitions and ending behaviour. If a project uses custom duration, the bridge may map project frames to renderer timing, but the renderer should not emit invented animation after its defined sequence.

## Intro

The renderer can define a canonical intro frame count. `RendererBridge` maps the three project intro modes:

- renderer default: direct canonical renderer frame path;
- disabled: skip canonical intro frames;
- custom: play custom intro frames, then resume at the canonical comparison start.

## Precision mode

`frame-exact` means the renderer expects canonical settings and deterministic frame behaviour. It does not automatically prove pixel identity; verification against the reference material is still required.

## Assets

Renderer-owned assets should be packaged/referenced in a way the installer can materialise safely. Project artwork remains separate card content and may come from MegaPacks or user-selected files.

## Compatibility

A bundle should declare the renderer API it targets. Import should reject or clearly report unsupported future/legacy APIs instead of partially interpreting them.

## Adding an engine

When a declarative renderer cannot be represented by an existing engine:

1. implement the new native engine in the Android codebase;
2. define the engine's supported declarative fields/tracks;
3. keep rendering deterministic for a given frame;
4. make preview and export use the same path;
5. add tests/reference validation;
6. document the engine here before publishing renderer bundles that depend on it.
