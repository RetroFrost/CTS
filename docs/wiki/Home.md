# Cubical Compare Wiki

Cubical Compare is a native Android comparison-video editor built around renderer bundles, MegaPacks and exact timeline rendering.

This wiki is split into two tracks:

## Using Cubical Compare

- [Installation](Installation.md)
- [Creating a project](Creating-a-Project.md)
- [Preview and transform editor](Preview-and-Transform.md)
- [MegaPacks](MegaPacks.md)
- [Renderer bundles](Renderer-Bundles.md)
- [Exporting video](Exporting-Video.md)
- [Troubleshooting](Troubleshooting.md)

## Developing Cubical Compare

- [Developer overview](Developer-Overview.md)
- [Project architecture](Architecture.md)
- [Building from source](Building-from-Source.md)
- [Renderer API v2](Renderer-API-v2.md)
- [MegaPack format](MegaPack-Format.md)
- [StudioProject schema](StudioProject-Schema.md)
- [Transform coordinate system](Transform-System.md)
- [Preview and export pipeline](Preview-and-Export-Pipeline.md)
- [CI and releases](CI-and-Releases.md)
- [Contributing](Contributing.md)

## Core principles

Cubical Compare is intentionally Python-free on Android. Rendering, preview, import and export use native Kotlin/Android paths. Renderer bundles are declarative and sandboxed; they do not ship executable code.

Exact renderers should preserve the source video's geometry, timing and animation rather than substitute generic motion.
