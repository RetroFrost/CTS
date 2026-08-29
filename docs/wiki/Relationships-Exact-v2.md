# Relationships Exact v2

`relationships-exact` bundles can opt in to the source-precision interpreter by adding this tag:

```text
relationships.exact.v2=true
```

The v2 interpreter exists for renderers reconstructed from a measured reference video. It keeps the renderer declarative: source-specific geometry, colours, typography, badge construction and frame motion stay in the `.renderer` bundle instead of being hardcoded in the app.

Existing relationships bundles that do not opt in continue to use the legacy relationships interpreter.

## Canonical geometry

The existing `RendererSpec` fields become authoritative in v2:

- `slotPitch`
- `bodyInset`
- `bodyWidth`
- `imageHeight`
- `titleHeight`
- `descriptionTop`
- `titleTextSize`
- `descriptionTextSize`
- `badgeCenterX`, `badgeCenterY`, `badgeScale`
- `badgeHeaderSize`, `badgeValueSize`, `badgeUnitSize`

`card.absoluteBands=true` makes `imageHeight`, `titleHeight` and `descriptionTop` absolute reference-canvas boundaries. This is the default for v2.

The gap between the title bottom and `descriptionTop` is rendered as a divider. Its colour is set by:

```text
card.divider.color=#D67D00
```

Additional card tags:

```text
card.imageFallbackColor=#1E1E1E
card.title.padX=10
card.title.padTop=1
card.title.padBottom=1
card.title.maxLines=1
card.title.lineHeight=0.92
card.description.padX=11
card.description.padTop=4
card.description.padBottom=4
card.description.maxLines=4
card.description.lineHeight=0.92
```

## Typography

Each role can use either an Android font family or an embedded font contained by the renderer manifest.

Family form:

```text
font.title.family=sans-serif
font.title.style=bold
```

Embedded form:

```text
font.title.asset=sourceBold
font.asset.sourceBold.base64=<base64 TTF/OTF bytes>
```

Supported roles are:

- `intro`
- `title`
- `description`
- `badge`
- `disclaimer`
- `outro`
- `outroSubscribe`

An embedded typeface takes priority over a family fallback. The bundle is still declarative and does not load executable code.

## Badge construction

A badge may supply an exact polygon as relative x/y pairs from the badge centre:

```text
badge.points=-92,-177,92,-177,184,-88,184,86,92,177,-92,177,-184,86,-184,-88
```

If `badge.points` is omitted, these geometry tags define the standard eight-sided shape:

```text
badge.radiusX=184
badge.radiusY=177
badge.cornerX=92
badge.upperCornerY=88
badge.lowerCornerY=86
```

Visual controls:

```text
badge.gradient.top=#D30F0E
badge.gradient.bottom=#D30F0E
badge.gradient.startY=-177
badge.gradient.endY=177
badge.stroke.color=#A60008
badge.stroke.width=4
badge.shadow.color=#00000000
badge.shadow.radius=0
badge.shadow.dx=0
badge.shadow.dy=0
badge.shine.width=78
badge.shine.slant=52
badge.shine.alpha=1
badge.header.y=-75
badge.value.y=12
badge.unit.y=70
badge.header.maxWidth=230
badge.value.maxWidth=300
badge.unit.maxWidth=245
badge.header.minSize=12
badge.value.minSize=18
badge.unit.minSize=12
badge.defaultHeader=1 in
badge.defaultUnit=People
```

Badge fill base colour, dark colour, text colour and shine colour continue to use `badgeColor`, `badgeDarkColor`, `badgeTextColor` and `shineColor` from `RendererSpec`.

## Frame-addressed tracks

Tracks use the existing renderer timeline. For frame-exact bundles the timeline unit is `frames`.

Conveyor:

```text
relationships.scroll.0
relationships.scroll.1
relationships.scroll.2
...
```

Each scroll segment contains at most 4096 keyframes. Segment selection remains `(frame - continuousStartFrame) / 4096`.

Card overrides:

```text
card.<index>.x
card.<index>.y
card.<index>.body.reveal
```

Badge overrides:

```text
card.<index>.badge.x
card.<index>.badge.y
card.<index>.badge.scale
card.<index>.badge.shine.x
card.<index>.badge.shine.alpha
```

Reusable local badge tracks:

```text
relationships.badge.y
relationships.badge.scale
relationships.badge.shine.x
relationships.badge.shine.alpha
```

Intro:

```text
relationships.intro.logo.scale
relationships.intro.logo.alpha
relationships.intro.text.chars
```

Disclaimer:

```text
relationships.disclaimer.x
relationships.disclaimer.alpha
```

Outro:

```text
relationships.outro.card.x
relationships.outro.panel.alpha
relationships.outro.question.chars
relationships.outro.comment.chars
relationships.outro.subscribe.alpha
relationships.outro.fade.alpha
```

`relationships.content_end` continues to set the canonical content/outro boundary.

## Intro and outro tags

The precision path also exposes the static visual constants that previously lived in Kotlin. They may be overridden with tags under these namespaces:

```text
intro.logo.*
intro.text.*
disclaimer.*
outro.panel.*
outro.question.*
outro.comment.*
outro.subscribe.*
```

This allows a measured reference to provide exact logo geometry, colours, text positions, panel bounds and timing while keeping the Android interpreter reusable.

## Backwards compatibility

The v2 path is opt-in. A bundle without `relationships.exact.v2=true` is rendered exactly as it was before this addition.
