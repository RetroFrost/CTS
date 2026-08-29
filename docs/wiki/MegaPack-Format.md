# MegaPack Format

MegaPacks are ZIP archives used to move a complete comparison dataset and its artwork into Cubical Compare.

## Goals

A MegaPack should be:

- self-contained;
- portable between devices;
- safe to extract into app-managed storage;
- explicit about card/artwork mapping;
- independent of machine-specific absolute paths.

## Recommended layout

A pack should keep data and artwork in predictable paths, for example:

```text
pack.zip
├── data.*
└── artwork/
    ├── card-001.png
    ├── card-002.png
    └── dimensions.json   # optional
```

The importer is responsible for converting supported data into `StudioCard` objects and resolving bundled images into materialised Android file paths.

## Artwork defaults

MegaPack artwork should normally use:

```text
imageLayer = "behind"
```

The background/artwork fills the artwork region, while the badge remains above the artwork. Characters, objects, liquids, icons and other subjects should be composed into the artwork layer rather than rendered over the badge.

For icon-oriented packs, use a consistent Lineal Color style unless the pack intentionally defines a different visual language.

## Per-card transform fields

The project model supports these artwork fields:

```text
imageX
imageY
imageScale
imageRotation
imageCropLeft
imageCropTop
imageCropRight
imageCropBottom
imageLayer
```

Importers should preserve explicitly supplied values and use neutral defaults when values are omitted.

Neutral transform:

```text
x = 0
y = 0
scale = 1
rotation = 0
crop = 0 on all edges
layer = behind
```

## `artwork/dimensions.json`

A pack may provide dimensions/transform information for assets. Dimensions can be asset-specific or shared where appropriate. Consumers must treat omitted optional metadata as optional rather than rejecting an otherwise valid pack.

## Paths

Inside the archive, reference assets by relative path. Never publish a MegaPack that requires paths such as `/home/...`, `C:\...`, or an Android app-private path from the machine that created it.

## Import behaviour

On Android, the selected ZIP is copied/materialised, then artwork is extracted into an app-managed destination. The resulting `StudioProject` contains normal local image paths, so renderer code does not need to read directly from the original ZIP.

## Backwards compatibility

When extending the format:

- add optional fields where possible;
- preserve existing field meanings;
- give new fields neutral defaults;
- bump an explicit format/version field if an incompatible interpretation is unavoidable;
- keep old packs importable when practical.

## Validation

A pack generator should verify before distribution:

- every referenced artwork file exists;
- card count matches data rows;
- transform values are finite and within sensible ranges;
- JSON is valid UTF-8;
- paths stay inside the archive root;
- duplicate or unsafe `..` extraction paths are rejected.
