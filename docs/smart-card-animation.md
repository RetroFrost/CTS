# Smart Card Animation

Smart Card Animation is the card-level companion to Smart Badge Animation. It is designed for source-exact comparison renderers where the card shell, reveal geometry, shadow and shine need to follow a frame-addressed reference sequence while the project's artwork and text remain live.

## Package layout

```text
renderer.renderer3
smart-cards/
  opening/
    desc.txt
    smart.json
    part0/
      0000.png
      0001.png
      ...
```

The frame plates must contain only card structure/effects. Do not bake source artwork, titles or descriptions into runtime plates.

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

`artwork.destRects` describes the full artwork destination for each template frame. `artwork.clipRects` describes the visible artwork window for that same frame, so reveal animation does not rescale the project artwork while it uncovers. The plate is then composited over the live artwork, preserving shell/shadow/shine effects.

Title and description fields use live project data. Description fields are wrapped by the Relationships typography renderer instead of being baked into the sequence.

## Exact-frame safety

Zero-padded numeric frame folders are validated for contiguous indexes. Missing or duplicate numeric frames are rejected rather than silently skipped. The same bootanimation-style `desc.txt` part rules used by Smart Badge Animation apply to Smart Card Animation.
