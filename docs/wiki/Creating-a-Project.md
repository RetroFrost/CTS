# Creating a Project

## Start a project

Use **More → New project** or open an existing `.ccproject.json` file. A project contains the card list plus video, audio, intro and encoder settings.

## Add and edit cards

The **Cards** page lets you:

- add, duplicate and delete cards;
- edit title, badge header, value and description;
- choose or replace artwork;
- adjust artwork transforms.

Empty text fields are valid. Renderers may reclaim the unused space instead of reserving blank text regions.

## Card artwork

Each card owns its own artwork path and transform. The transform fields are position X/Y, scale, rotation, crop on all four edges, and layer position.

For MegaPack-style artwork, the normal composition rule is that the artwork/background fills the available artwork region and characters or objects remain underneath the badge, with the badge rendered above them.

## Project duration

Choose **Automatic** to let the active renderer define the canonical timeline length, or **Custom** to set a project duration. When the duration changes, timeline speed is adjusted rather than inventing additional animations after the renderer's intended sequence.

## Intro modes

- **Renderer default** — uses the renderer's own canonical intro.
- **Custom MP4** — replaces the renderer intro with a chosen MP4.
- **Disabled** — removes the intro and begins directly at comparison content.

Disabled does not insert blank replacement frames.

## Soundtrack

Choose an audio file under **Project → Audio**, set volume, and enable or disable looping.

## Saving

Cubical Compare autosaves edits locally. Use **More → Save project** to create a portable `.ccproject.json` copy you can archive or move to another device.
