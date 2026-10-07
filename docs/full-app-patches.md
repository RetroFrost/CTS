# Full-app patches (Windows 4.2.15)

Developer has its own navigation tab. It contains C# renderer overrides, full-app patch import/restore, reference calibration and build diagnostics. Normal renderer loading remains in Style & Model; update settings remain in Settings.

## Importing a patch

1. Open **Developer → Import app patch…** and select one or more `.patch` files.
2. The importer applies them in the selected order to a disposable copy of this build's bundled source. Review the changed files and patch order.
3. Choose **Build & restart**. The app restores dependencies and publishes the complete Windows app, including its XAML UI and all modules. Build scripts and imported source run with your account's permissions.
4. The existing app remains active during the build. Cancel stops the compiler and leaves it installed. Build errors retain the log at the path shown in the developer panel.
5. After a successful build, the workspace is saved and the current app is backed up. The existing ZIP update service validates, stages and activates the new app, then restarts it. **Restore app before last patch** reinstalls the backup and restarts.

Full builds require Windows, .NET 10 SDK (not just the runtime), PowerShell 7 and the Windows desktop build tools used to compile WinUI. Dependency restoration may need network access. A packaged runtime alone cannot compile XAML and replace the entire app. Older releases without the bundled `Assets/AppSource.zip` need a normal app update before they can import patches.

## Format and version matching

Supported: UTF-8 unified text diffs and Git format-patch text changes, additions, deletions, renames with text hunks, multiple hunks and multiple files. Existing UTF-8 BOMs, CRLF and final-newline behavior are preserved. Hunk positions and context must match exactly; a patch for another source version is rejected, rather than partially applied to the installed app.

Binary patches and mode-only/rename-only diffs are rejected explicitly. Use a release ZIP for binary changes. Paths must stay within the source workspace and cannot traverse links, use Windows device names or alternate streams. All hunks in each patch validate before any of that patch's files are changed. A failure in a later selected patch discards the job; the installed app is untouched.

App patches are full source rebuilds, not renderer hot swaps. C# overrides retain their separate import and restore controls. Rolling back the app does not erase C# override bundles or project data. New patch builds contain their patched source snapshot so subsequent patches target the code that is actually installed.

## Validation

`dotnet run --project windows/CubicalCompare.PatchTests` checks exact context, file operations, multiple hunks, line endings/BOM/EOF markers, unsafe paths, links, cancellation, ordered imports and source isolation. On Windows, `--full-build PATH_TO_AppSource.zip` applies a real XAML patch and rebuilds/packages the entire app. Release CI also checks Developer navigation and page isolation through the live WinUI window.
