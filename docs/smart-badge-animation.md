# Smart Badge Animation — Renderer v3

Cubical Compare 4.2.1.1 adds **Smart Badge Animation** under Smart Features.

The goal is to reproduce a badge's reference animation exactly without baking the reference video's text or surrounding scene into the renderer. A Renderer v3 package can carry a transparent badge-only frame sequence and let Cubical Compare draw the current project's badge text on top.

## Package layout

The frame sequence deliberately borrows the useful directory model from Android `bootanimation.zip`:

```text
renderer.renderer3
smart-badges/
  opening/
    desc.txt
    smart.json
    part0/
      0000.png
      0001.png
      0002.png
      ...
```

Runtime frames should be tightly cropped around the badge, use transparency outside the badge, and contain **no baked source text**.

### desc.txt

```text
474 384 60
p 1 0 part0
```

The first line is `width height fps`.

Each following line is:

```text
type count pause folder
```

Cubical Compare 4.2.1.1 supports `p` and `c` parts. `count=0` loops forever. `pause` is measured in frames.

Unlike Android's boot animation service, Renderer v3 packages are already ZIP resources loaded into memory, so Smart Badge sequences do not require STORE/no-compression ZIP entries.

## smart.json and jsparse

Every replaceable text field must use the exact marker token `jsparse`.

```json
{
  "marker": "jsparse",
  "transparentBackground": true,
  "emptyText": true,
  "fields": [
    {
      "name": "header",
      "source": "header",
      "token": "jsparse",
      "rect": [88, 66, 298, 54],
      "fontSize": 39,
      "minFontSize": 18,
      "color": "#ffffff",
      "align": "center"
    },
    {
      "name": "value",
      "source": "value",
      "token": "jsparse",
      "rect": [74, 126, 326, 124],
      "fontSize": 91,
      "minFontSize": 30,
      "color": "#ffffff",
      "align": "center"
    },
    {
      "name": "unit",
      "source": "unit",
      "token": "jsparse",
      "rect": [92, 260, 290, 58],
      "fontSize": 39,
      "minFontSize": 18,
      "color": "#ffffff",
      "align": "center"
    }
  ]
}
```

Supported live sources include `header`, `value`, `unit`, `fullValue`, and `title`.

The `jsparse` token is an authoring marker, not user-visible export text. Calibration/reference marker frames may show `jsparse` in each field so a pack-building tool can locate the replaceable regions, but the runtime `part*` frames must be the cleaned badge plates.

## Per-frame field tracking

For reference animations where the badge changes size or position, replace a static `rect` with `rects`. Each entry corresponds to the selected template frame. Use `null` when a field should not yet be visible.

```json
{
  "name": "value",
  "source": "value",
  "token": "jsparse",
  "rects": [
    null,
    null,
    [95, 170, 210, 70],
    [84, 150, 250, 86],
    [74, 126, 326, 124]
  ],
  "alphas": [0, 0, 0.35, 0.72, 1],
  "rotations": [0, 0, -2.1, -0.8, 0],
  "fontSize": 91,
  "minFontSize": 30,
  "color": "#ffffff"
}
```

This lets project text follow a reference-derived badge animation while the badge shell itself is played as exact transparent frames.

## Renderer resource

```json
{
  "resources": {
    "badgeOpening": {
      "type": "smart-badge-animation",
      "sequenceRoot": "smart-badges/opening",
      "sampling": "high"
    }
  }
}
```

An object using the resource must resolve to a card index using `cardIndex`, `dataIndex`, or an id ending in `@<index>`.

The object can animate normal Renderer v3 transforms and may also provide a numeric or tracked `sequenceFrame` property for explicit frame addressing.

## Validation

When a package declares any of these features:

- `smart-badge-animation-v1`
- `smart-badge-jsparse-v1`
- `bootanimation-frame-sequence-v1`

Cubical Compare validates that a smart-badge resource exists, `desc.txt` and `smart.json` are present, the sequence has actual frame images, the background/text flags are safe, every replaceable field uses the literal `jsparse` marker, and each Smart Badge object can be mapped to project data.

## Frame-exact playback

For source-exact sequences, use zero-padded numeric frame names such as `0000.png`, `0001.png`, and so on. Cubical Compare 4.2.1.5+ sorts numeric sequence frames by their numeric index and rejects missing or duplicate indexes instead of silently skipping them.

When the sequence FPS matches the renderer reference FPS, Smart Badge Animation is frame-locked by default: renderer frame N selects sequence frame N directly. Set `"frameLock": false` only when intentional FPS conversion is required. A 60 FPS reference contains one unique source frame every 16.667 ms; Cubical Compare reproduces every source frame rather than inventing intermediate millisecond states.

A Smart Badge resource can state this explicitly:

```json
{
  "type": "smart-badge-animation",
  "sequenceRoot": "smart-badges/opening",
  "frameLock": true,
  "sampling": "high"
}
```
