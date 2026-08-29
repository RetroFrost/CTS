# MegaPacks

MegaPacks are portable ZIP bundles that can populate a complete comparison project with card data and artwork.

## What a MegaPack contains

A MegaPack normally includes:

- card data;
- artwork files;
- optional per-card dimensions/transform metadata;
- project-friendly defaults.

Cubical Compare extracts imported MegaPacks into app-managed storage and maps the bundled assets onto `StudioCard` entries.

## Artwork composition rule

The default Cubical Compare MegaPack composition is:

1. the background/artwork fills the complete artwork region;
2. characters, objects, liquids, icons or other subjects are part of that artwork composition;
3. the badge is rendered above the artwork;
4. therefore the artwork layer should normally be `behind`, not `front`.

Artwork intended as icons should use a consistent **Lineal Color** visual style unless the pack deliberately specifies another style.

## Transform metadata

A MegaPack can define per-card artwork transforms. Supported values include:

- X/Y position;
- scale;
- rotation;
- crop left/top/right/bottom;
- layer (`behind` or `front`).

These values survive import into the project and are used by preview and export.

## Dimensions metadata

When a pack contains artwork/dimensions metadata, dimensions can be specified for individual assets or shared across assets. Cropped or overflow areas can be styled by the renderer or artwork itself; the exact behaviour depends on the active renderer.

## Importing

Open **More → MegaPack**, choose the ZIP and wait for extraction. The resulting project keeps the imported card text and artwork but preserves relevant current UI/export preferences where supported.

## Portability

Do not rely on temporary external paths inside a MegaPack. Assets should be contained in the ZIP and referenced relative to the pack so the importer can materialise them safely on Android.
