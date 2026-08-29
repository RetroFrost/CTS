# StudioProject Schema

`StudioProject` is the portable editor state consumed by preview and export.

## Project fields

Current project-level concepts include:

| Field | Meaning |
|---|---|
| `name` | Project display/file name |
| `cards` | Ordered list of `StudioCard` entries |
| `width` / `height` | Output resolution |
| `fps` | Output frame rate |
| `showBadges` | Whether badge rendering is enabled |
| `creditsEnabled` | Whether renderer/project credits are enabled |
| `introMode` | Renderer, custom MP4 or disabled |
| `introVideo` | Materialised custom intro path |
| `soundtrack` | Persisted audio URI/path reference |
| `soundtrackVolume` | 0–1 soundtrack gain |
| `soundtrackLoop` | Whether soundtrack loops |
| `autoLength` | Use renderer-derived duration |
| `customLengthSeconds` | Manual duration when auto length is off |
| `encoderPreference` | Auto / H.264 / H.265 |

## Card fields

Each `StudioCard` contains:

| Field | Meaning |
|---|---|
| `id` | Stable card identity |
| `title` | Main title text |
| `value` | Primary badge/value text |
| `badgeHeader` | Optional badge header |
| `description` | Optional description |
| `image` | Materialised artwork path |
| `imageX` / `imageY` | Artwork translation |
| `imageScale` | Uniform artwork scale |
| `imageRotation` | Degrees |
| `imageCropLeft` | Left crop fraction |
| `imageCropTop` | Top crop fraction |
| `imageCropRight` | Right crop fraction |
| `imageCropBottom` | Bottom crop fraction |
| `imageLayer` | Usually `behind`, optionally `front` |

## JSON serialisation

Portable projects are written as JSON. The current serialiser includes:

- top-level `version`;
- project `name`;
- `cards` array;
- `settings` object;
- renderer/model lock information.

Card field names use snake_case in JSON, for example `badge_header`, `image_scale` and `image_crop_left`.

## Model lock

A saved project can contain a model lock identifying the intended model/renderer profile. Importers and future migrations should preserve this where possible rather than silently rebinding a project to an unrelated renderer.

## Compatibility rules

When changing `StudioProject`:

1. keep neutral defaults for newly added fields;
2. update both serialisation and parsing;
3. preserve older project readability;
4. avoid encoding transient UI state into the portable format;
5. keep renderer-specific runtime caches out of the project JSON.

## IDs

Card IDs should remain stable across ordinary edits. Duplication creates a new ID. A renderer/editor may use IDs to preserve UI state or selection across list edits.

## Transform units

Transform values are renderer-space values, not raw screen pixels from the current phone. See [Transform System](Transform-System.md) for mapping details.
