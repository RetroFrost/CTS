# Cubical Compare V5 for Windows

This is a **clean Windows rebuild** (.NET 8 WPF), isolated under windows-v5. Android and old Windows editions remain untouched.

Implemented: app icon, optional compatibility rendering for old GPUs (WPF software mode), automatic system/dark/light theme with Windows 11 Mica request, redesigned 3-column UI, 10 UI languages, per-card badge colours, InkCanvas draw/erase artwork, import local images, CSV and project open/save, 1080p MP4 export via FFmpeg, and an interactive NSIS installer.

The **Main image / Thumbnail** editor tabs now share the same scale, X/Y, rotation and flip controls. A thumbnail can reuse its card image or use a separate image. Thumbnail-only transformations do not affect the video card. Both preview and exported 1920×1080 thumbnail PNG use the identical WPF transformed image, so no untransformed thumbnail export path remains. Main-card images can also be positioned by dragging directly on the canvas, and transforms survive project save/open. Each canvas has independent drawing ink.

The Windows Actions pipeline additionally performs a WPF thumbnail regression that changes thumbnail transformation, compares before/after exported PNG pixels, verifies video-frame invariance, and validates saved transform JSON before publishing a GitHub release.

To build locally on Windows with .NET 8 SDK:

    powershell -NoProfile -ExecutionPolicy Bypass -File windows-v5/tools/make-icon.ps1
    dotnet publish windows-v5/CubicalCompareV5/CubicalCompareV5.csproj -c Release -r win-x64 --self-contained true -o windows-v5/publish

After merge into v5/development, GitHub Actions builds portable ZIP and GUI installer and makes a new pre-release. No Velopack, no silent installs. Export needs ffmpeg.exe alongside app exe or on PATH; the self-contained app opens without it.

**Windows 11 Creativity category:** Windows 11 automatically classifies Start menu items; NSIS has no supported setting. For Microsoft Store listing, category **Multimedia design → Photo + video production** is appropriate. Microsoft documents that Store onboarding is the route for most developers. For developers in the EU only, reportapp@microsoft.com can accept an app-category request stating "Creativity", the exe name and display name. No unsupported Windows Start registry modification is attempted. Docs: https://learn.microsoft.com/en-us/windows-hardware/customize/desktop/customize-the-windows-11-start-menu

Limitations: MP4 export is a 2-second-per-card slideshow (not a Renderer3 animation engine), artwork import is local file based, FFmpeg is external, and unsupported systems fall back from Mica. Windows 11 Start > Creativity placement is controlled by Windows and Microsoft Store metadata, not by NSIS.
## Update experience

V5 never shows an in-app changelog or “What's New” dialog after installing an update. The NSIS installer retains its normal **visible** installation pages and progress bar, and may offer to launch the app after installation. Release notes are available only on the GitHub Releases page when the user chooses to visit it. The older Windows Settings embedded changelog has also been removed.

## Floating Fluent navigation and audio patterns

The Windows V5 top row uses a compact, rounded **floating Fluent navigation island**: Project, Cards, Thumbnail, Audio, Export, Draw and Settings. These are real controls, not a design mockup. Project opens New/Open/Save/Import. Cards restores the editor. Thumbnail switches to the independent transformed PNG editor. Audio opens the ordered soundtrack panel. Export invokes MP4. Draw enables artwork inking. Settings scrolls to appearance, language and GPU compatibility settings.

The Audio panel offers Add/Remove, Move Up/Down, volume, repeat whole pattern and fade at video end. **Save Pattern (.m3u)** stores ordered UTF-8 local file references, relative to the playlist when possible. **Import Pattern (.m3u/.m3u8)** restores the exact order, including intentional repeats. Missing files are flagged and block video export. M3U files contain references, not audio data.

Projects (.ccv5) preserve track order, volume, pattern repeat and fade. FFmpeg normalises mixed soundtracks, concatenates them in order and muxes them with video. The entire pattern repeats if selected. Fade out is on by default. Audio export requires FFmpeg; playlist saving/importing does not.

Windows CI checks the absence of in-app changelog popups, builds and launches the app, exercises floating navigation and M3U regression, validates thumbnail-only transformations, packages a visible NSIS installer and publishes a GitHub prerelease.
