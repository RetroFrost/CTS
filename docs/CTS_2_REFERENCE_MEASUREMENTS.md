# CTS 2.0 reference-model measurement contract

CTS 2.0 treats the two supplied comparison videos as immutable visual specifications. The app UI may select a model and supply card content/artwork, but it must not alter model-owned colors, geometry, typography metrics, cadence, animation, badge treatment, disclaimer, outro, or output frame clock.

The canonical MP4 files are **not** embedded in the repository or app.

## Full-video analysis coverage

Both canonical videos were decoded from beginning to end during the CTS 2.0 measurement pass. Every source frame was included in the scan.

| Model | Canonical frames | FPS | Visual duration |
|---|---:|---:|---:|
| What Males Learn At Each Age | 16,741 | 60 | 279.0166667 s |
| Types Of Relationships | 11,130 | 60 | 185.5000000 s |

Total measured source frames: **27,871**.

## What Males Learn At Each Age

Measured opening card starts: frames **0, 120, 240, 360**. The fourth-card / opening-credit handoff continues through the low-500s and enters the continuous conveyor around frame 528.

The opening-to-conveyor handoff is not represented as a generic easing curve. Measured card-width shifts used by Android CTS 2.0 are:

| Frame | Card-width shift |
|---:|---:|
| 528 | 0.000000 |
| 535 | 0.035055 |
| 540 | 0.047559 |
| 550 | 0.089242 |
| 560 | 0.160102 |
| 570 | 0.230962 |
| 580 | 0.301822 |
| 590 | 0.385186 |
| 600 | 0.464382 |
| 610 | 0.535242 |
| 620 | 0.614439 |

After frame 620 the measured separator clock settles into the long conveyor. The fitted period is approximately **214.14294 frames per card width**. For the canonical 78-card source, shift reaches exactly **74 card widths at frame 16,335**.

The final card group then holds for **37 frames** before the end sequence starts at frame **16,372**. The measured end sequence is:

- cover/wipe: 25 frames
- end-group rise: 23 frames
- hold: 273 frames
- fade: 48 frames

These segments end exactly at canonical frame **16,741**.

Older Males badges de-emphasize as newer badges become active; that stage scaling is part of the model animation and is not an app setting.

## Types Of Relationships

The opening identity animation occupies frames **0..373**. The first content frame is **374**.

Measured identity-animation details retained by CTS:

- dual loop/circle motion begins around frame 34 and settles by frame 373
- `Infinite` title typing occurs approximately frames 240..290
- `Comparison` title typing occurs approximately frames 288..350
- opening card phases start at frames **374, 521, 656, 795**
- continuous card phase begins at frame **896**
- the final continuous/sweep handoff occurs at canonical frame **10,738**

The Relationships ending uses its own measured sweep and is intentionally not shared with the Males conveyor model. The end-screen watch panel is locked to the measured reference geometry (canonical 1920x1080 coordinates approximately x=1314, y=79, w=552, h=892), with the measured question/comment/subscribe typing clocks.

## Renderer ownership

For both shipped reference models, these are renderer-owned and not user-overridable:

- output frame clock and native reference speed
- model colors and gradients
- card geometry and dividers
- title/description band geometry
- badge geometry, motion, shine and de-emphasis
- intro/disclaimer behavior
- conveyor position clock
- final-card transition
- end-screen geometry and movement
- fade/tail behavior

User artwork is content inside the model-defined artwork slot. It may not replace the model's full-card background or palette.

## Preview/export parity

Compose preview and MediaCodec export must consume the same timeline/model measurements. Preview-only edit handles, selection borders, dynamic Material colors, or other application UI state must never enter the rendered video image.

Dynamic Material 3 / Material 3 Expressive applies only to the CTS application shell.
