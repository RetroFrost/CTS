# CTS reference-model measurement contract

CTS ships one immutable visual reference model: **What Males Learn At Each Age**. Its canonical source is `Comparison： Evolution Of Language (400,000 BC - 2026).mp4`.

The MP4 is not embedded in the app. Its identity and visual clock are locked as follows:

| Property | Contract |
|---|---:|
| SHA-256 | `965d878c8343f820a66d34129c8a998de6a8039fed110a7f5fc1fd622ee355b2` |
| Canvas | 1920 × 1080 |
| Frame rate | 60 FPS |
| Visual frames | 12,267 |
| Visual duration | 204.45 s |
| Canonical cards | 57 |

## Contact-sheet measurements

The full 120-frame sheet and dense opening/outro sheets establish these integer-frame anchors:

| Event | Source frame |
|---|---:|
| Opening card starts | 0, 120, 240, 360 |
| Continuous conveyor starts | 528 |
| Canonical conveyor position holds | 11,841 |
| Outro starts | 11,858 |
| Black cover first becomes visible | 11,868 |
| End group first becomes visible | 11,901 |
| End group settles | 11,911 |
| Fade starts | 12,180 |
| Fully black | 12,258 |
| Black tail | 12,259–12,266 |

The measured slot pitch is **476 px**. Each body is **471 px** wide at a **9 px** inset. Artwork occupies 872 px vertically, followed by a model-owned **93 px opaque light title band** and a 115 px description band. Empty text bands collapse into artwork space.

## Renderer ownership

These properties are renderer-owned and not user-overridable:

- integer output frame clock and native playback speed
- card geometry, light title bands, description bands, and dividers
- badge geometry, entry deformation, text trail, shine, and de-emphasis
- opening credits and continuous conveyor positions
- top-down outro cover, end-screen entrance, measured fade, and black tail
- 1920 × 1080 output at 60 FPS

User artwork stays inside the model-defined artwork slot. Compose preview and MediaCodec export consume the same immutable `ReferenceScene`; application theme state and editing affordances never enter the encoded frame.
