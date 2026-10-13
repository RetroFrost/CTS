# Cubical Compare V5 for Windows

This is a **clean Windows rebuild** (.NET 8 WPF), isolated under windows-v5. Android and old Windows editions remain untouched.

Implemented: app icon, optional compatibility rendering for old GPUs (WPF software mode), automatic system/dark/light theme with Windows 11 Mica request, redesigned 3-column UI, 10 UI languages, per-card badge colours, InkCanvas draw/erase artwork, import local images, CSV and project open/save, 1080p MP4 export via FFmpeg, and an interactive NSIS installer.

To build locally on Windows with .NET 8 SDK:

    powershell -NoProfile -ExecutionPolicy Bypass -File windows-v5/tools/make-icon.ps1
    dotnet publish windows-v5/CubicalCompareV5/CubicalCompareV5.csproj -c Release -r win-x64 --self-contained true -o windows-v5/publish

After merge into v5/development, GitHub Actions builds portable ZIP and GUI installer and makes a new pre-release. No Velopack, no silent installs. Export needs ffmpeg.exe alongside app exe or on PATH; the self-contained app opens without it.

**Windows 11 Creativity category:** Windows 11 automatically classifies Start menu items; NSIS has no supported setting. For Microsoft Store listing, category **Multimedia design → Photo + video production** is appropriate. Microsoft documents that Store onboarding is the route for most developers. For developers in the EU only, reportapp@microsoft.com can accept an app-category request stating "Creativity", the exe name and display name. No unsupported Windows Start registry modification is attempted. Docs: https://learn.microsoft.com/en-us/windows-hardware/customize/desktop/customize-the-windows-11-start-menu

Limitations: MP4 export is a 2-second-per-card slideshow (not a Renderer3 animation engine), image import is local file based, FFmpeg is external, and unsupported systems fall back from Mica.