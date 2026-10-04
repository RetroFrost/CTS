using CubicalCompare.Core.Project;
using CubicalCompare.Core.Renderer;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private int _rendererLoadRevision;

    private async Task<bool> LoadEditorRendererAsync(string path)
    {
        var revision = ++_rendererLoadRevision;
        var renderer = await RendererPackageProbe.InspectAsync(path);
        if (revision != _rendererLoadRevision) return false;
        if (renderer.Generation is not (RendererGeneration.V2 or RendererGeneration.V3))
        {
            RendererCompatibilityText.Text = "This renderer is not handled by the v2/v3 compatibility evaluator.";
            return false;
        }
        var replacement = await Task.Run(() => LegacyRendererAdapter.Load(path));
        if (revision != _rendererLoadRevision) { replacement.Dispose(); return false; }
        StopPreviewPlayback();
        _legacyRenderer?.Dispose();
        _legacyRenderer = replacement;
        RendererNameText.Text = renderer.Name;
        RendererGenerationText.Text = $"Renderer v{(int)renderer.Generation} · API {renderer.Api}";
        RendererEngineText.Text = $"Engine {renderer.Engine}";
        RendererCanvasText.Text = $"Reference {renderer.ReferenceWidth}×{renderer.ReferenceHeight} · {renderer.ReferenceFps} FPS";
        RendererSourceText.Text = path;
        RendererCompatibilityText.Text = $"Renderer v{replacement.Api} compatibility evaluator active.";
        RefreshTimelineRange();
        RefreshSoundtrackUi();
        ProjectFrameSlider.Value = replacement.InitialPreviewFrame(BuildProject());
        TimelineStatusText.Text = $"Renderer v{replacement.Api} · {replacement.Name}";
        await RenderCurrentFrameAsync();
        return revision == _rendererLoadRevision;
    }

    private async Task RememberEditorRendererAsync(string source)
    {
        var directory = Path.Combine(AppDataPaths.RootDirectory, "LastRenderer");
        Directory.CreateDirectory(directory);
        var cached = Path.Combine(directory, "last" + Path.GetExtension(source));
        var temporary = cached + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            if (!Path.GetFullPath(source).Equals(cached, StringComparison.OrdinalIgnoreCase))
            {
                await using (var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, true))
                await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, true))
                    await input.CopyToAsync(output);
                File.Move(temporary, cached, overwrite: true);
            }
            AppPreferences.Set("LastEditorRenderer", cached);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    private async Task RestoreLastEditorRendererAsync()
    {
        if (_legacyRenderer is not null || Environment.GetCommandLineArgs().Any(argument => argument.StartsWith("--ci-", StringComparison.Ordinal))) return;
        var cached = AppPreferences.GetString("LastEditorRenderer", "");
        if (string.IsNullOrWhiteSpace(cached) || !File.Exists(cached)) return;
        try
        {
            TimelineStatusText.Text = "Loading previous renderer…";
            await LoadEditorRendererAsync(cached);
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not restore the previous renderer", ex);
            TimelineStatusText.Text = "Previous renderer could not be restored. Load a renderer to continue.";
        }
    }
}
