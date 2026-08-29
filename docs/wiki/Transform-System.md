# Transform System

Artwork transforms are stored per card and interpreted in renderer space so the same project behaves consistently across phone preview sizes and full-resolution export.

## Stored transform

A card carries:

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

## Coordinate space

`imageX` and `imageY` are not literal Compose-screen pixels. Pointer movement is converted from preview viewport space into the active renderer's reference coordinate system.

This matters because a 640×360 preview and a 1920×1080 export must produce the same logical transform.

## Translation

For direct preview editing, a pan delta is mapped approximately as:

```text
rendererDeltaX = pointerDeltaX / previewWidth  * referenceWidth
rendererDeltaY = pointerDeltaY / previewHeight * referenceHeight
```

The stored position is then clamped to a safe range to prevent unbounded values.

## Scale

Scale is uniform and multiplicative:

```text
newScale = oldScale × gestureZoom
```

Corner handles also modify the same `imageScale` value. This keeps handle resizing and pinch resizing compatible.

## Rotation

Rotation is stored in degrees and normalised into a practical range around −180°…180° for editing. Multi-touch twist and the dedicated rotation interaction should modify the same value.

## Crop

Crop values are fractions of the source artwork edges. They are separate from scale: cropping changes what portion of the artwork is visible, while scaling changes the size of the surviving artwork within the renderer's artwork region.

## Layer

`behind` means the artwork participates below the badge; `front` allows a renderer to place it above relevant badge layers where supported.

MegaPack content should normally use `behind`.

## Direct preview editor

The transform editor follows this sequence:

1. hit-test a visible renderer card at the current frame;
2. copy that `StudioCard` to local `draft` state;
3. gestures update only `draft`;
4. a temporary project is produced with the draft card substituted at the selected index;
5. the real renderer redraws the preview frame;
6. Compose draws selection guides/handles only;
7. Done commits the card once to `StudioProject`;
8. Cancel discards it.

This architecture is important: drawing a separate Compose image over the renderer would lie about crop and badge layering.

## Apply from card onward

The bulk transform action copies only transform/layer fields from the draft card to card `N…lastIndex`. It must not copy the image path or textual card content.

## Hit testing

Hit testing should derive visible slot/card geometry from `RendererSpec`, including reference width, slot pitch, body inset and body width. Do not hard-code phone-screen card rectangles.

## Exactness

If a renderer has non-standard artwork bounds or animated card geometry, extend the renderer geometry API rather than adding renderer-specific magic constants to the generic editor.
