# Smart Card Animation

Smart Card Animation is the card-level companion to Smart Badge Animation. It is designed for source-exact comparison renderers where the card shell, reveal geometry, shadow and shine need to follow a frame-addressed reference sequence while the project's artwork and text stay live.

## Package layout

```text
renderer.renderer3
smart-cards/
  opening/
    desc.txt
    smart.json
    base/
      0000.png
      0001.png
      ...
    overlay/
      0000.png
      0001.png
      ...
```

The `base/` frames contain the card shell/underlay only. The `overlay/` frames contain glass, shine and other effects that must pass over live artwork and text.

Do not bake project artwork, title text or description text into either runtime sequence.

## desc.txt

The header is **width height fps**:

```text
480 1080 60
p 1 0 base
```

The normal bootanimation-style part rules apply. Zero-padded numeric frames are recommended.

## Resource

```json
{
  "type": "smart-card-animation",
  "sequenceRoot": "smart-cards/opening",
  "slotPitch": 480,
  "drawWidth": 480,
  "drawHeight": 1080,
  "frameLock": true,
  "sampling": "high"
}
```

When the sequence FPS matches the renderer reference FPS and `frameLock` is enabled, renderer frame N selects sequence frame N exactly.

## smart.json

```json
{
  "marker": "jsparse",
  "transparentBackground": true,
  "emptyText": true,
  "overlayFolder": "overlay",
  "artwork": {
    "destRects": [[0,0,474,789]],
    "clipRects": [[0,0,474,120]],
    "alphas": [1]
  },
  "fields": [
    {
      "name": "title",
      "source": "title",
      "token": "jsparse",
      "rects": [[10,791,454,113]],
      "alphas": [1],
      "fontSize": 49,
      "minFontSize": 20,
      "bold": true,
      "color": "#111111"
    },
    {
      "name": "description",
      "source": "description",
      "token": "jsparse",
      "rects": [[16,924,442,146]],
      "alphas": [1],
      "fontSize": 28,
      "minFontSize": 12,
      "color": "#f5f5f5"
    }
  ]
}
```

`artwork.destRects` describes the full artwork destination for each template frame. `artwork.clipRects` describes the visible artwork window for that same frame, so a reveal uncovers the artwork rather than resizing it.

## Render order

SmartCard compositing is intentionally strict:

1. base/card-shell frame
2. live project artwork
3. live `jsparse` title/description fields
4. overlay/glass/shine frame

The overlay pass is last so the shine affects both the artwork and the live text, matching a source animation where the whole finished card is illuminated.

If `smart-card-layered-compositing-v1` is required by the renderer, `overlayFolder` is mandatory and must contain one overlay frame for every template frame.

## Exact-frame safety

Numeric frame folders are validated for contiguous indexes. Missing or duplicate frame indexes are rejected rather than silently skipped. The overlay frame count must exactly match the total base/template frame count.

Title and description fields use live project data. Description fields use the wrapped Relationships typography renderer rather than baked source text.
