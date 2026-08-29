# Architecture

Cubical Compare 2.0.7 is a native Android application organised around a persistent project model and a renderer runtime.

## High-level data flow

`StudioProject` → `RendererBridge` → active renderer engine → preview bitmap / RGBA frame → hardware video export

Imports create or update `StudioProject`. UI edits the project. Renderer code consumes the same project for preview and export.

## Important source files

### `MainActivity.kt`

Owns the primary Compose application shell and the four main areas: Cards, Preview, Project and More. It also owns document-picker launchers, project mutation plumbing, metadata refresh and debounced autosave state.

### `DirectPreviewTransform.kt`

Implements direct, video-editor-style artwork selection and transform interaction on top of a rendered frame. Draft changes stay local until commit.

### `StudioProject.kt`

Defines the portable project/card data model and JSON serialisation.

### `RendererBridge.kt`

Maps project frames to renderer frames, dispatches to the active renderer engine, handles intro modes and exposes render metadata/import helpers to the UI/export code.

### `RendererBundle.kt`

Defines/loads renderer bundle metadata and the renderer API contract.

### Renderer implementations

Native renderer implementations include the generic renderer and exact-model engines. Their purpose is to make a frame deterministic from `(renderer spec, project, frame, output size)`.

### `NativeImporters.kt`

Contains native data/MegaPack import paths.

### `ExportService.kt`

Runs export outside the main editor interaction and publishes progress/cancel state.

### `HardwareVideoExporter.kt`

Owns the hardware video encoding path and frame submission to Android media codecs.

### `HardwareAudioTranscoder.kt`

Handles soundtrack/audio processing used by export.

### `IntroVideoSource.kt`

Provides custom MP4 intro frames and related caching/decoding logic.

### `ProjectAutosave.kt`

Loads/saves the local autosave copy of the current project.

## UI state model

The root Compose app holds a `StudioProject` value. Normal field edits replace the project with a copied immutable value.

High-frequency transform gestures are intentionally different: they mutate a local `draft` card in the transform composable. `onProjectChange` is called only when the user commits. This prevents pointer-rate project recomposition, metadata work and autosave pressure.

## Metadata

Render metadata includes frame count, duration and frame rate. Metadata recalculation is keyed to timeline-affecting settings rather than every card text/transform edit.

## Renderer ownership

The renderer owns visual/timeline semantics. The editor should not recreate the renderer with approximate Compose widgets. In particular, direct transform mode should ask the real renderer to redraw the draft project so badge/artwork layering and crop behaviour remain identical to export.

## Media path

Preview requests smaller rendered bitmaps for responsiveness. Export renders the configured output size and feeds frames to the selected Android hardware codec. The renderer-default intro path is kept fast; custom video intros require additional decode work.

## Persistence

Projects can be:

- autosaved internally;
- saved as portable `.ccproject.json` documents;
- reconstructed from MegaPacks or spreadsheets.

Renderer installations and imported assets are materialised into app-managed storage so Android document URIs do not need to remain live forever.
