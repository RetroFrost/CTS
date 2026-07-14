# Changelog

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
- Added a prominent Add card button beside playback that creates and reveals a new card.
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
