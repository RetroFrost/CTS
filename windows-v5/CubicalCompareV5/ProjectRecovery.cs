using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;

namespace CubicalCompareV5;

// Recovery snapshots live outside the project and are never mistaken for a saved .ccv5.
internal sealed class RecoveryRecord
{
    public int RecoveryVersion { get; set; } = 1;
    public DateTime SavedUtc { get; set; } = DateTime.UtcNow;
    public string? OriginalPath { get; set; }
    public ComparisonProject Project { get; set; } = new();
}

internal static class ProjectRecovery
{
    internal static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "CubicalCompareV5", "recovery.json");

    internal static void Write(ComparisonProject project, string? original, string? destination = null)
    {
        var path = destination ?? FilePath;
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        var tmp = path + ".tmp";
        try
        {
            var record = new RecoveryRecord { OriginalPath = original, Project = project };
            File.WriteAllText(tmp, JsonSerializer.Serialize(record));
            if (File.Exists(path)) File.Replace(tmp, path, path + ".bak");
            else File.Move(tmp, path);
        }
        finally
        {
            if (File.Exists(tmp)) File.Delete(tmp);
        }
    }

    internal static bool TryRead(out RecoveryRecord? recovery, string? destination = null)
    {
        var path = destination ?? FilePath;
        foreach (var candidate in new[] { path, path + ".bak" })
        {
            try
            {
                if (!File.Exists(candidate)) continue;
                if (new FileInfo(candidate).Length > 512L * 1024 * 1024) continue;
                var data = JsonSerializer.Deserialize<RecoveryRecord>(File.ReadAllText(candidate));
                if (data is not { RecoveryVersion: 1 } || data.Project is null ||
                    data.Project.FormatVersion != 5 || data.Project.Cards is null) continue;
                recovery = data;
                return true;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException or NotSupportedException)
            {
                // A truncated latest save must not hide its intact prior snapshot.
            }
        }
        recovery = null;
        return false;
    }

    internal static void Clear(string? destination = null)
    {
        var path = destination ?? FilePath;
        foreach (var file in new[] { path, path + ".tmp", path + ".bak" })
            try { if (File.Exists(file)) File.Delete(file); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
    }

    internal static void RunRegressionChecks()
    {
        string path = Path.Combine(Path.GetTempPath(),
            "cubical-v5-recovery-" + Guid.NewGuid().ToString("N"), "recovery.json");
        try
        {
            var project = new ComparisonProject();
            project.Cards.Add(new ComparisonCard
            {
                Title = "Recovery test", ThumbnailArtworkBase64 = "image-data",
                ThumbnailTransform = new ArtworkTransformState { X = -88, Scale = 1.5 }
            });
            project.AudioTracks.AddRange(new[] { "first.wav", "second.mp3", "first.wav" });
            Write(project, @"C:\projects\test.ccv5", path);
            if (!TryRead(out var first, path) || first?.Project.Cards[0].Title != "Recovery test")
                throw new InvalidDataException("Recovery round-trip failed.");
            project.Cards[0].Title = "Second checkpoint";
            Write(project, null, path);
            File.WriteAllText(path, "{ incomplete json");
            if (!TryRead(out var restored, path) || restored?.Project.Cards[0].Title != "Recovery test")
                throw new InvalidDataException("Corrupt latest autosave did not fall back to prior checkpoint.");
            if (restored.Project.Cards[0].ThumbnailTransform.X != -88 ||
                restored.Project.AudioTracks.Count != 3 ||
                restored.OriginalPath != @"C:\projects\test.ccv5")
                throw new InvalidDataException("Recovery lost media settings, transform, or original path.");
            Clear(path);
            if (File.Exists(path) || File.Exists(path + ".bak"))
                throw new IOException("Recovery cleanup did not remove the snapshots.");
        }
        finally
        {
            Clear(path);
            try { Directory.Delete(Path.GetDirectoryName(path)!, true); } catch (IOException) { }
        }
    }
}

public partial class MainWindow
{
    private readonly DispatcherTimer _recoveryTimer = new() { Interval = TimeSpan.FromSeconds(30) };
    private string _savedCheckpoint = "";
    private bool _recoveryEnabled;

    private string CurrentProjectJson() => JsonSerializer.Serialize(BuildProjectSnapshot());
    private bool HasUnsavedChanges() => CurrentProjectJson() != _savedCheckpoint;

    private void ResetRecoveryCheckpoint()
    {
        _savedCheckpoint = CurrentProjectJson();
        if (_recoveryEnabled) ProjectRecovery.Clear();
    }

    private void InitializeRecovery()
    {
        // Regression runs must not read, modify, or delete the user's real recovery file.
        var args = Environment.GetCommandLineArgs();
        bool test = args.Any(a => a.StartsWith("--self-test-", StringComparison.OrdinalIgnoreCase));
        if (test)
        {
            if (args.Contains("--self-test-recovery", StringComparer.OrdinalIgnoreCase))
                Loaded += (_, _) => Dispatcher.BeginInvoke(new Action(() =>
                {
                    try
                    {
                        ProjectRecovery.RunRegressionChecks();
                        File.WriteAllText(Path.Combine(Environment.CurrentDirectory, "recovery-regression-result.txt"), "PASS");
                        Environment.Exit(0);
                    }
                    catch (Exception ex)
                    {
                        File.WriteAllText(Path.Combine(Environment.CurrentDirectory, "recovery-regression-result.txt"), ex.ToString());
                        Environment.Exit(1);
                    }
                }), DispatcherPriority.ApplicationIdle);
            return;
        }

        _savedCheckpoint = CurrentProjectJson();
        _recoveryEnabled = true;
        _recoveryTimer.Tick += (_, _) => SaveRecoveryIfChanged();
        Loaded += (_, _) =>
        {
            RestoreRecoveryIfAvailable();
            _recoveryTimer.Start();
        };
        Closing += (_, _) => SaveRecoveryIfChanged();
        PreviewKeyDown += RecoveryShortcutKeyDown;
    }

    private void RecoveryShortcutKeyDown(object sender, KeyEventArgs e)
    {
        if (Keyboard.Modifiers != ModifierKeys.Control || e.Key != Key.S) return;
        e.Handled = true;
        SaveProject();
    }

    private void RestoreRecoveryIfAvailable()
    {
        if (!ProjectRecovery.TryRead(out var recovery) || recovery is null) return;
        var name = string.IsNullOrEmpty(recovery.OriginalPath)
            ? "untitled project" : Path.GetFileName(recovery.OriginalPath);
        var answer = System.Windows.MessageBox.Show(this,
            "Recover unsaved changes from " + name + "?\nSaved: " +
            recovery.SavedUtc.ToLocalTime().ToString("g") +
            "\nChoose No to discard the recovery snapshot.",
            "Restore Cubical Compare project", MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (answer == MessageBoxResult.Yes)
        {
            ReplaceCards(recovery.Project.Cards);
            LoadAudioFromProject(recovery.Project);
            _projectPath = recovery.OriginalPath;
            UpdateTitle();
            StatusLabel.Text = "Recovered unsaved project. Use Ctrl+S to save it.";
            // Retain the recovery file until the user saves explicitly.
        }
        else ProjectRecovery.Clear();
    }

    private void SaveRecoveryIfChanged()
    {
        if (!_recoveryEnabled || _exporting) return;
        try
        {
            PersistInk();
            if (!HasUnsavedChanges()) { ProjectRecovery.Clear(); return; }
            ProjectRecovery.Write(BuildProjectSnapshot(), _projectPath);
            StatusLabel.Text = "Unsaved project backed up for recovery.";
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            StatusLabel.Text = "Project recovery backup failed: " + ex.Message;
        }
    }
}
