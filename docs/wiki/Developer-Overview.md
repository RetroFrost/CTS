# Developer Overview

This section documents the native Android Cubical Compare codebase for contributors and renderer authors.

## Repository

Main repository: `RetroFrost/CTS`

The active 2.0.7 native work is developed on the `fork/2.0.7-renderer-bundles` branch.

## Native-only Android runtime

The Android app is intentionally Python-free. CI rejects Android runtime changes that introduce a Python source tree, Chaquopy, `PyApplication` or `com.chaquo` integration.

The native app is primarily Kotlin/Jetpack Compose plus Android media APIs.

## Main subsystems

- **UI / editor** — Compose screens in `MainActivity.kt` and supporting editor components.
- **Project model** — `StudioProject` and `StudioCard`.
- **Renderer runtime** — renderer bundle loading, renderer selection and frame rendering.
- **Exact renderer engines** — model-specific native engines such as Relationships and Ribbon.
- **Importers** — spreadsheet, MegaPack and renderer import paths.
- **Preview** — frame rendering plus direct transform editor.
- **Export** — background export service, hardware video encoder and audio pipeline.
- **Intro source** — renderer/default/custom/disabled intro mapping.
- **Autosave** — debounced project persistence.

## Development philosophy

1. Keep renderer-specific animation logic out of generic UI when it can live in renderer data/engine code.
2. Exact renderers must be deterministic at a given project/frame.
3. Preview and export should share renderer semantics.
4. Pointer gestures should mutate local draft state; commit once rather than rebuilding the whole project on every event.
5. Avoid full-resolution bitmap decoding in UI components.
6. Prefer hardware/media APIs for Android export.
7. Do not silently approximate unsupported renderer behaviour.

## Where to start

If you are changing the app UI, begin with [Architecture](Architecture.md).

If you are adding a renderer, begin with [Renderer API v2](Renderer-API-v2.md).

If you are creating/importing content, see [MegaPack Format](MegaPack-Format.md) and [StudioProject Schema](StudioProject-Schema.md).

If you are changing the renderer/editor interaction, read [Transform System](Transform-System.md) and [Preview and Export Pipeline](Preview-and-Export-Pipeline.md).
