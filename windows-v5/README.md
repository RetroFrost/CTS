# Cubical Compare V5 — Windows

V5 Windows is built from the **full WinUI 3 V4 implementation** in windows/CubicalCompare,
not the stripped-down WPF rewrite. Build with -p:V5Preview=true and .NET 10.
This preserves existing renderer v2/v3, MegaPacks, thumbnails, soundtrack tools,
editor/export features and .ccproject support. The compiled executable is
CubicalCompare.exe (the original WinUI app host), not CubicalCompareV5.exe.
The visible NSIS installer and preview data directory are isolated from stable V4.
The original WPF V5 experiment was removed from this preview branch.
