# Preview and Transform Editor

The **Preview** page is both a frame-accurate viewer and a direct artwork editor.

## Playback

Use Play/Pause to preview the active renderer. The frame slider and `−1` / `+1` controls let you inspect exact frames. Playback speed presets are available for inspection.

## Select artwork directly

Tap visible artwork on the rendered frame. Cubical Compare resolves the card occupying that position at the current renderer frame and enters direct transform mode.

## Direct transform controls

While editing:

- drag the selected object to move it;
- pinch to scale;
- twist with two fingers to rotate;
- drag corner handles to resize;
- use the rotation handle when visible for deliberate single-finger rotation;
- use Reset to restore neutral transform values;
- use Done to commit the current card;
- use Cancel to discard the draft.

The renderer redraws the draft card while you edit, so badge layering, crop and card composition match the actual export path instead of a fake overlay approximation.

## Apply from the selected card onward

**Apply from card N onward** copies the current transform from the selected card through every later card.

Copied properties:

- image X and Y;
- scale;
- rotation;
- crop left/top/right/bottom;
- front/behind layer.

Not copied:

- artwork image path;
- title;
- badge header;
- value;
- description;
- card identity.

This is useful when a whole section of cards uses artwork with consistent framing.

## Editing performance

Transform gestures are kept in local draft state. Cubical Compare does not write the full project or trigger autosave on every pointer event. A project update happens only when the transform is committed, preventing the heavy state churn that earlier builds suffered from.

## Layering

For normal MegaPack compositions, artwork should remain **behind the badge**. The background still fills the artwork region; characters, objects and other visual subjects are part of that artwork composition and the badge is drawn above them.
