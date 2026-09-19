# Changelog

## 4.2.1.3 — 2026-09-19

- Removed the blocking Velopack **OK** acknowledgement from in-app package updates.
- Cubical Compare now keeps its own update progress visible during download/staging, then hands off to Velopack's silent file-swap phase and restarts automatically.
- The user-facing updater no longer exposes the internal Velopack package version during the apply phase.
- CI now downloads the previous Velopack release before packing and generates `BestSpeed` delta packages whenever possible.
- Normal installed updates report and prefer the fast delta path automatically; full packages remain the reliable fallback when no compatible delta exists.
- Reduced the public Setup wrapper's post-install delay before launching Cubical Compare.
- Installed copies still prefer verified `.nupkg` + `releases.win.json` updates when available, with Setup.exe/portable fallbacks retained.
- Added a CI guard so package updates cannot regress back to `ApplyUpdatesAndRestart` and its blocking completion dialog.


## 4.2.1.2 — 2026-09-19

- Replaced the fixed **Update from ZIP** behaviour with an automatic reliability-first updater.
- When a release includes a valid full `.nupkg` and `releases.win.json`, real Velopack-managed installs automatically prefer the package updater.
- Installed copies automatically fall back to the visible Setup.exe when the package feed cannot be used safely.
- Raw/portable copies use the portable ZIP path instead of pretending they are Velopack installs.
- Portable ZIP updates are downloaded with retry handling, SHA-256 checked when GitHub supplies a digest, fully extracted and validated before the running app exits, and protected by a full rollback copy.
- Restored publication of the Velopack full `.nupkg` and `releases.win.json` assets so future installed updates can actually use the package path.
- The Updates UI now always exposes one **Install update** action and reports which method was selected automatically.
- Visible/non-silent Setup behaviour remains unchanged.


## 4.2.1.1 — 2026-09-19

- Added **Smart Features** to Settings and added an in-app built-in changelog.
- Added **Smart Badge Animation** for Renderer v3.
- Added transparent badge-only frame sequences using a bootanimation-style `desc.txt` plus `part0`, `part1`, etc. frame folders.
- Added `smart.json` sidecars that identify every replaceable badge text field with the literal marker `jsparse`.
- Added live replacement of `jsparse` fields from project data, including badge header, primary value and unit/suffix.
- Added per-frame field rectangles, opacity tracks and rotation tracks so live text can stay aligned to reference-derived badge animation frames.
- Smart badge runtime frames must be clean/empty text plates with transparent backgrounds; calibration/reference marker frames are not composited into exports.
- Added native validation for Smart Badge sequence packages and the new `smart-badge-animation-v1`, `smart-badge-jsparse-v1`, and `bootanimation-frame-sequence-v1` renderer capabilities.
- Kept the public Windows installer visible and non-silent.


## 4.2.1 — 2026-09-19

- Added a 20-feature Renderer Accuracy Pack for Renderer v3.
- Added native support for source-exact opening and outro overlay sequences.
- Added validation for verified opening/outro boundaries and the 266-frame Relationships conveyor cadence.
- Added source-region raster cropping, native-size raster drawing, selectable filter quality, premultiplied-alpha source raster handling, deterministic alpha rounding, and extended Skia blend modes.
- Added optional deterministic pixel snapping while preserving subpixel transforms by default.
- Added clip anti-alias control, local-space clipping, and deterministic per-object z-index ordering.
- Added local frame offsets, dense-track stride support, explicit step interpolation, and explicit linear interpolation.
- Kept preview/export on the same Renderer v3 evaluator path and retained visible, non-silent Windows setup behaviour.
- SourceLocked **Types of Relationships** packages no longer need the temporary developer capability shim for their source-verification feature flags.


## 0.4.5 — 2026-07-14

- Promoted the redesigned editing workspace to the CTS 0.4.5 release line.
- Replaced the separate XLSX action with one **Import file** action for UTF-8 CSV and XLSX data.
- Added CSV quoted-field support and kept automatic field mapping, direct spreadsheet editing, and image workflows intact.
- Added right-click transformation for text and images with four-corner resize handles and drag-to-move behavior.
- Added live visual feedback while moving and resizing transformed objects.
- Added reliable deselection through Escape, click-away behavior, monitor-margin clicks, and an explicit object-menu action.
- Made moved text and images selectable again at their transformed on-screen positions.
- Saved per-card transform overrides in CTS project files and applied the same transformed layout during MP4 export.
- Added a global **Show hexagons** checkbox for every visual model.
- Added model-specific no-hexagon reflow so titles, descriptions, and artwork expand into the released badge space instead of leaving a hole.
- Added subtle shadows to Illustrated hexagons and white title bars.
- Improved badge text fitting so ordinary words stay intact whenever possible while still supporting extreme unbroken strings.
- Kept transparent PNG/WebP artwork alpha, project-wide font selection, Illustrated backgrounds, image scaling, and automatic/manual hexagon sizing.
- Preserved the 0.3.5 data workflow, model migration, soundtrack export mixing, deterministic preview/export rendering, timing, and FFmpeg progress reporting.

## 0.4.0

- Rebuilt the application shell as a dense, professional editing workspace while preserving the complete CTS 0.3.5 workflow and behavior.
- Moved project data, visual models, and soundtrack controls into a compact Project panel beside a larger Program Monitor.
- Made **Click to Insert Data** the primary action without changing the existing clipboard-table parser or spreadsheet workflow.
- Restyled playback, sequence length, animation controls, tables, tabs, dialogs, menus, and export actions with an original dark editing-suite design.
- Added five new Illustrated Cards backgrounds alongside Beach: Sunset, Forest, Lavender, Night, and Blueprint Grid.
- Added a project-wide system font picker used by preview and export.
- Added manual image scaling from 50% to 200% for Reference Detail, Illustrated Cards, and Classic Compact.
- Added manual Illustrated Cards hexagon scaling from 60% to 160%.
- Added optional text-aware Illustrated sizing that gives longer values more hexagon room and slightly adjusts artwork scale.
- Preserved transparent image alpha so selected Illustrated backgrounds can remain visible behind artwork.
- Saved and restored the new visual controls in CTS project files while keeping older 0.3.5 projects compatible.
- Kept the layout responsive and practical on 1366×768 displays.
- Left the 0.3.5 data workflow, direct editing, model migration, soundtrack mixing, timing, and MP4 export behavior intact.

## 0.3.5

- Made the in-place text indicator sample the rendered field beneath it.
- Added automatic high-contrast cyan or violet editor text, caret, selection, and underline colors.
- Kept the editor transparent and embedded directly in the card without restoring a popup box.

## 0.3.4

- Added a screen-aware startup size that fits inside the desktop's available geometry.
- Added a compact layout for 1366×768 and other laptop-size displays.
- Reduced the preview minimum from 640×360 to 480×270 while preserving the 16:9 picture.
- Made the Models tab vertically scrollable so advanced controls never disappear below the screen.
- Reduced margins and header height dynamically and shortened header actions on narrow windows.

## 0.3.3

- Redesigned the workspace with a compact application bar, clearer panel headings, and grouped preview controls.
- Made Classic Compact shrink, wrap, and safely ellipsize long titles, values, units, and unbroken strings.
- Added **Paste image URL** to the direct image menu and normalized copied URLs.
- Improved remote-image compatibility with browser-like request headers, query-string URLs, `file://` URLs, and readable size/error handling.

## 0.3.2

- Fixed the in-place preview editor crash caused by `CardData` not being imported in the UI module.
- Restored direct editing for badge, title, description, and typed image-path fields.

## 0.3.1

- Replaced the floating direct-edit input with a transparent, borderless caret positioned inside the exact rendered field.
- Temporarily blanks only the active rendered value so in-place text never overlaps the old text.
- Added normalized editor rectangles for badges, titles, descriptions, images, and partially scrolled cards.
- Added the project-persistent **Hexagons bounce** checkbox in its own Animation row below the preview.
- Kept badge motion separate from the visual-model selector so it cannot be mistaken for a model.
- When bounce is disabled, badges keep a fixed scale during entrances and scrolling.
- Expanded regression coverage for in-place editor geometry, scrolling, persistence, and both badge-motion modes.

## 0.3.0

- Turned the rendered preview into a direct visual card editor.
- Added animation-aware hit-testing that resolves clicks to the correct card and semantic field.
- Added inline text editing over the preview with Enter-to-apply and Escape-to-cancel.
- Added a direct image menu for file selection, typed paths/URLs, and clearing artwork.
- Added a prominent Add card button beside playback that creates and reveals another card.
- Changed the initial preview to a stable fully visible editing frame instead of the black first animation frame.
- Kept all spreadsheet, XLSX, clipboard, mapping, strip-splitting, and soundtrack workflows synchronized.
- Added hit-region and scrolled-card regression tests for all three visual models.

## 0.2.2

- Replaced the generic one-column startup grid with model-owned spreadsheet schemas.
- Switching visual models now reshapes the table and migrates compatible mapped values.
- Added an always-visible, model-specific plain-language field guide and header tooltips.
- Reworked row controls into Add card, Duplicate, and Delete card.
- Reworked column controls into Add field, Rename field, Delete field, and New blank table.
- Added cell/header context menus for card actions, field actions, and direct visual-role mapping.
- Protected active model fields from accidental renaming or deletion while allowing their cells to remain blank.
- Preserved non-empty imported fields as advanced extras instead of discarding data.

## 0.2.1

- Fixed a startup crash caused by Models-tab signals firing before the Soundtrack tab
  had finished constructing its master-volume control.
- Added an explicit UI-ready boundary so other cross-tab initialization signals are
  safely ignored until the complete editor exists.

## 0.2.0

- Removed required spreadsheet columns and the fixed four-field table.
- Removed generated `UPLOADED`, `DATE`, and `Untitled` content from rendered cards.
- Added arbitrary columns, column editing, generic XLSX/clipboard import, and optional
  per-model field mapping.
- Added Illustrated Cards and Classic Compact alongside Reference Detail.
- Added native or custom 1–8-card viewport layouts.
- Added multi-track soundtrack trimming, placement, looping, fades, volume, mixing,
  AAC export, and soundtrack-stage progress.
- Added version-2 project persistence with automatic version-1 migration.
- Preserved divider-aware image-strip assignment and readable error boundaries.

## 0.1.1

- Improved 2-pixel divider detection to reject repeated uniform bands inside artwork.

## 0.1.0

- Initial reference-model editor, XLSX import, strip splitting, preview, and MP4 export.
