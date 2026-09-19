# SmartBadge v2

SmartBadge v2 lets a single Renderer v3 package embed multiple bootanimation-style badge ZIPs and select a different badge animation for every card.

## Outer renderer layout

```text
renderer.renderer3   # or renderer.renderer for a wrapped exact legacy/ribbon renderer
smartbadge-v2.json
badgepacks/
  opening-0.zip
  opening-1.zip
  opening-2.zip
  opening-3.zip
  later.zip
```

A nested badge ZIP uses the existing Smart animation contract:

```text
desc.txt
smart.json
part0/
  0000.webp
  0001.webp
  ...
hold/
  0000.webp
overlay/
  0000.webp
  0001.webp
  ...
```

The base frames contain the badge shell/shadow/reveal only. Live text is inserted with `jsparse`. When `overlayFolder` is declared, its frame is composited **after** the live text, so shine/glass effects brighten the text too.

## smartbadge-v2.json

```json
{
  "version": 2,
  "defaultPack": "later",
  "packs": {
    "opening-0": { "asset": "badgepacks/opening-0.zip" },
    "opening-1": { "asset": "badgepacks/opening-1.zip" },
    "later": { "asset": "badgepacks/later.zip" }
  },
  "cards": {
    "0": { "pack": "opening-0", "startFrame": 0 },
    "1": { "pack": "opening-1", "startFrame": 120 },
    "2": { "pack": "later", "startFrame": 240 }
  }
}
```

Every entry under `cards` independently selects a pack. Packs may be shared by many cards, or a renderer may provide one unique ZIP per card.

A pack definition can additionally set `drawX`, `drawY`, `drawWidth`, and `drawHeight`.

## Nested smart.json

```json
{
  "marker": "jsparse",
  "transparentBackground": true,
  "emptyText": true,
  "overlayFolder": "overlay",
  "fields": [
    {
      "name": "value",
      "source": "value",
      "token": "jsparse",
      "rects": [[88,160,318,105]],
      "alphas": [1],
      "fontSize": 78,
      "minFontSize": 28,
      "bold": true,
      "color": "#ffffff"
    },
    {
      "name": "unit",
      "source": "unit",
      "token": "jsparse",
      "rects": [[82,274,326,58]],
      "alphas": [1],
      "fontSize": 40,
      "minFontSize": 18,
      "bold": true,
      "color": "#ffffff"
    }
  ]
}
```

## Validation and safety

Cubical Compare 4.2.1.7+ validates the outer pack map and every nested ZIP. Numeric frame sequences must be contiguous. Nested packs are bounded by file-count, per-entry and total expanded-size limits. Unsafe paths are rejected.

If a selected SmartBadge v2 pack is invalid at render time, Cubical Compare fails closed for that badge instead of silently replacing it with a different procedural badge.
