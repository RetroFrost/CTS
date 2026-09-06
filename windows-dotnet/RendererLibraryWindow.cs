using Microsoft.Win32;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace CubicalCompare.Windows;

public sealed record InstalledRendererInfo(RendererSpec Spec, string FilePath, string Sha256, bool Active, RendererValidationReport Report);

public sealed class RendererLibraryService
{
    private readonly string _root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CubicalCompare", "renderers");
    private string Library => Path.Combine(_root, "library");
    private string ActivePath => Path.Combine(_root, "active.renderer");
    private string PreviousPath => Path.Combine(_root, "previous.renderer");

    public string ActiveSha256() => File.Exists(ActivePath) ? Sha(File.ReadAllBytes(ActivePath)) : "";

    public IReadOnlyList<InstalledRendererInfo> ListInstalled()
    {
        Directory.CreateDirectory(Library);
        var activeSha = ActiveSha256();
        var result = new List<InstalledRendererInfo>();
        foreach (var path in Directory.EnumerateFiles(Library, "*.renderer", SearchOption.TopDirectoryOnly))
        {
            try
            {
                var candidate = RendererBundleReader.Inspect(path);
                result.Add(new InstalledRendererInfo(candidate.Spec, path, candidate.Sha256, candidate.Sha256.Equals(activeSha, StringComparison.OrdinalIgnoreCase), candidate.Report));
            }
            catch { }
        }
        return result.OrderBy(x => x.Spec.Name, StringComparer.OrdinalIgnoreCase).ToList();
    }

    public RendererSpec ActivateFile(string path)
    {
        var candidate = RendererBundleReader.Inspect(path);
        if (!candidate.Report.Compatible) throw new InvalidOperationException(string.Join(Environment.NewLine, candidate.Report.Errors));
        return new RendererStore().Activate(candidate);
    }

    public void Uninstall(InstalledRendererInfo item)
    {
        if (item.Active) throw new InvalidOperationException("Activate another renderer before deleting the active renderer.");
        if (File.Exists(item.FilePath)) File.Delete(item.FilePath);
    }

    public RendererSpec Rollback()
    {
        if (!File.Exists(PreviousPath)) throw new InvalidOperationException("There is no previous renderer to restore.");
        var previous = File.ReadAllBytes(PreviousPath);
        var spec = RendererBundleReader.Read(previous);
        var report = RendererCapabilities.Report(spec);
        if (!report.Compatible) throw new InvalidOperationException(string.Join(Environment.NewLine, report.Errors));
        Directory.CreateDirectory(_root);
        var current = File.Exists(ActivePath) ? File.ReadAllBytes(ActivePath) : null;
        AtomicWrite(ActivePath, previous);
        if (current != null) AtomicWrite(PreviousPath, current); else File.Delete(PreviousPath);
        return RendererBundleReader.Read(previous);
    }

    public void ExportActive(string destination)
    {
        if (!File.Exists(ActivePath)) throw new InvalidOperationException("The built-in renderer has no standalone bundle to export.");
        File.Copy(ActivePath, destination, true);
    }

    public string InstalledSha256(string id)
    {
        var path = Path.Combine(Library, Sanitize(id) + ".renderer");
        return File.Exists(path) ? Sha(File.ReadAllBytes(path)) : "";
    }

    private static string Sanitize(string value) => string.Concat(value.Select(c => char.IsLetterOrDigit(c) || c is '.' or '_' or '-' ? c : '_'));
    private static string Sha(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    private static void AtomicWrite(string path, byte[] bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp = path + ".tmp"; File.WriteAllBytes(tmp, bytes); using (var stream = new FileStream(tmp, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) stream.Flush(true); File.Move(tmp, path, true);
    }
}

public sealed record OfficialRendererEntry(string Id, string Name, string Description, string Author, string Url, string Sha256, string MinAppVersion);

public sealed class RendererLibraryWindow : Window
{
    private const string CatalogUrl = "https://raw.githubusercontent.com/RetroFrost/CTS/renderer-repository/index.json";
    private const string OfficialPrefix = "https://raw.githubusercontent.com/RetroFrost/CTS/renderer-repository/renderers/";
    private const int MaxCatalogBytes = 256 * 1024;
    private const int MaxRendererBytes = 8 * 1024 * 1024;
    private readonly RendererLibraryService _library = new();
    private readonly RendererStore _store = new();
    private readonly Action _onChanged;
    private readonly StackPanel _installed = new();
    private readonly StackPanel _official = new();
    private readonly TextBlock _active = new();
    private readonly TextBlock _message = new() { TextWrapping = TextWrapping.Wrap };
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(25) };

    public RendererLibraryWindow(Action onChanged)
    {
        _onChanged = onChanged;
        Title = "Cubical Compare renderers";
        Width = 860; Height = 760; MinWidth = 560; MinHeight = 480; WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Background = new SolidColorBrush(Color.FromRgb(27, 27, 29)); Foreground = Brushes.White;
        var root = new DockPanel { Margin = new Thickness(16) }; Content = root;
        var top = new StackPanel { Margin = new Thickness(0, 0, 0, 12) }; DockPanel.SetDock(top, Dock.Top); root.Children.Add(top);
        top.Children.Add(new TextBlock { Text = "Renderer library", FontSize = 25, FontWeight = FontWeights.SemiBold });
        _active.Foreground = new SolidColorBrush(Color.FromRgb(150, 210, 255)); top.Children.Add(_active);
        _message.Margin = new Thickness(0, 5, 0, 0); _message.Foreground = new SolidColorBrush(Color.FromRgb(195, 195, 195)); top.Children.Add(_message);
        var tools = new WrapPanel { Margin = new Thickness(0, 8, 0, 0) }; top.Children.Add(tools);
        tools.Children.Add(Button("Inspect / import", Import));
        tools.Children.Add(Button("Export active", ExportActive));
        tools.Children.Add(Button("Restore previous", RestorePrevious));
        tools.Children.Add(Button("Built-in", RestoreBuiltIn));

        var tabs = new TabControl(); root.Children.Add(tabs);
        tabs.Items.Add(new TabItem { Header = "Installed", Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = _installed } });
        var officialRoot = new DockPanel();
        var refresh = Button("Refresh official repository", async () => await RefreshOfficialAsync()); DockPanel.SetDock(refresh, Dock.Top); officialRoot.Children.Add(refresh);
        officialRoot.Children.Add(new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = _official });
        tabs.Items.Add(new TabItem { Header = "Official repository", Content = officialRoot });
        Loaded += async (_, _) => { RefreshInstalled(); await RefreshOfficialAsync(); };
    }

    private void RefreshInstalled()
    {
        _installed.Children.Clear();
        var activeSpec = _store.Active();
        _active.Text = $"Active: {activeSpec.Name} • {activeSpec.Engine} • API {activeSpec.RendererApi}";
        var items = _library.ListInstalled();
        if (items.Count == 0) _installed.Children.Add(Info("No custom renderers installed."));
        foreach (var item in items)
        {
            var card = Card();
            var head = new DockPanel(); card.Children.Add(head);
            if (item.Active) { var badge = new TextBlock { Text = "ACTIVE", FontWeight = FontWeights.Bold, Foreground = new SolidColorBrush(Color.FromRgb(100, 210, 255)) }; DockPanel.SetDock(badge, Dock.Right); head.Children.Add(badge); }
            head.Children.Add(new TextBlock { Text = item.Spec.Name, FontSize = 17, FontWeight = FontWeights.SemiBold });
            card.Children.Add(Info($"{item.Spec.Engine} • {item.Spec.PrecisionMode} • API {item.Spec.RendererApi} • by {item.Spec.Author}"));
            card.Children.Add(Info(item.Report.Compatible ? $"Compatible • SHA-256 {item.Sha256[..Math.Min(16, item.Sha256.Length)]}…" : "Not compatible: " + string.Join("; ", item.Report.Errors)));
            var actions = new WrapPanel { Margin = new Thickness(0, 7, 0, 0) }; card.Children.Add(actions);
            var use = Button("Use", () => { try { _library.ActivateFile(item.FilePath); Changed($"Activated {item.Spec.Name}."); } catch (Exception ex) { Error(ex); } }); use.IsEnabled = !item.Active && item.Report.Compatible; actions.Children.Add(use);
            var delete = Button("Delete", () => { try { _library.Uninstall(item); Changed($"Deleted {item.Spec.Name}."); } catch (Exception ex) { Error(ex); } }); delete.IsEnabled = !item.Active; actions.Children.Add(delete);
            _installed.Children.Add(new Border { Margin = new Thickness(0, 0, 0, 10), Padding = new Thickness(12), BorderThickness = new Thickness(1), BorderBrush = new SolidColorBrush(Color.FromRgb(65,65,68)), CornerRadius = new CornerRadius(10), Child = card });
        }
    }

    private void Import()
    {
        var dlg = new OpenFileDialog { Filter = "Cubical Compare renderers|*.renderer;*.renderer3;*.zip|All files|*.*" };
        if (dlg.ShowDialog(this) != true) return;
        try
        {
            var candidate = RendererBundleReader.Inspect(dlg.FileName);
            var dialog = new RendererImportDialog(candidate, dlg.FileName, _store) { Owner = this };
            dialog.ShowDialog();
            if (dialog.RendererActivated) Changed($"Activated {candidate.Spec.Name}."); else RefreshInstalled();
        }
        catch (Exception ex) { Error(ex); }
    }

    private void ExportActive()
    {
        var spec = _store.Active();
        var dlg = new SaveFileDialog { Filter = "Renderer bundle|*.renderer;*.renderer3|All files|*.*", FileName = spec.Id + (spec.RendererApi >= 3 ? ".renderer3" : ".renderer") };
        if (dlg.ShowDialog(this) != true) return;
        try { _library.ExportActive(dlg.FileName); _message.Text = "Active renderer exported."; }
        catch (Exception ex) { Error(ex); }
    }

    private void RestorePrevious()
    {
        try { var spec = _library.Rollback(); Changed($"Restored {spec.Name}."); } catch (Exception ex) { Error(ex); }
    }

    private void RestoreBuiltIn()
    {
        try { var spec = _store.Reset(); Changed($"Restored {spec.Name}."); } catch (Exception ex) { Error(ex); }
    }

    private async Task RefreshOfficialAsync()
    {
        _official.Children.Clear(); _official.Children.Add(Info("Loading official renderer repository…"));
        try
        {
            var bytes = await DownloadLimitedAsync(CatalogUrl, MaxCatalogBytes);
            var entries = ParseCatalog(bytes);
            _official.Children.Clear();
            if (entries.Count == 0) _official.Children.Add(Info("The official renderer repository is currently empty."));
            var installed = _library.ListInstalled().ToDictionary(x => x.Spec.Id, x => x.Sha256, StringComparer.Ordinal);
            var activeSha = _library.ActiveSha256();
            foreach (var entry in entries)
            {
                var card = Card();
                card.Children.Add(new TextBlock { Text = entry.Name, FontSize = 17, FontWeight = FontWeights.SemiBold });
                card.Children.Add(Info($"by {entry.Author} • requires {entry.MinAppVersion}+"));
                if (entry.Description.Length > 0) card.Children.Add(Info(entry.Description));
                var exactActive = entry.Sha256.Equals(activeSha, StringComparison.OrdinalIgnoreCase);
                var exactInstalled = installed.TryGetValue(entry.Id, out var sha) && entry.Sha256.Equals(sha, StringComparison.OrdinalIgnoreCase);
                var action = Button(exactActive ? "Active" : exactInstalled ? "Use" : "Download, verify & use", async () =>
                {
                    if (exactInstalled)
                    {
                        var item = _library.ListInstalled().FirstOrDefault(x => x.Spec.Id == entry.Id && x.Sha256.Equals(entry.Sha256, StringComparison.OrdinalIgnoreCase));
                        if (item != null) { _library.ActivateFile(item.FilePath); Changed($"Activated {entry.Name}."); }
                    }
                    else await DownloadAndUseAsync(entry);
                });
                action.IsEnabled = !exactActive; card.Children.Add(action);
                _official.Children.Add(new Border { Margin = new Thickness(0, 0, 0, 10), Padding = new Thickness(12), BorderThickness = new Thickness(1), BorderBrush = new SolidColorBrush(Color.FromRgb(65,65,68)), CornerRadius = new CornerRadius(10), Child = card });
            }
            _message.Text = $"{entries.Count} official renderer{(entries.Count == 1 ? "" : "s")} available. Downloads are checksum verified.";
        }
        catch (Exception ex) { _official.Children.Clear(); _official.Children.Add(Info("Could not load repository: " + ex.Message)); }
    }

    private async Task DownloadAndUseAsync(OfficialRendererEntry entry)
    {
        try
        {
            RequireOfficialUrl(entry.Url);
            if (entry.Sha256.Length != 64 || entry.Sha256.Any(c => !Uri.IsHexDigit(c))) throw new InvalidDataException("Catalog checksum is invalid.");
            _message.Text = $"Downloading {entry.Name}…";
            var bytes = await DownloadLimitedAsync(entry.Url, MaxRendererBytes);
            var actual = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
            if (!actual.Equals(entry.Sha256, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Renderer checksum mismatch. The download was not installed.");
            var temp = Path.Combine(Path.GetTempPath(), "CubicalCompare-renderer-" + Guid.NewGuid().ToString("N") + ".renderer");
            try
            {
                File.WriteAllBytes(temp, bytes);
                var candidate = RendererBundleReader.Inspect(temp);
                if (candidate.Spec.Id != entry.Id) throw new InvalidDataException("Renderer identity does not match the official catalog.");
                if (!candidate.Report.Compatible) throw new InvalidDataException(string.Join(Environment.NewLine, candidate.Report.Errors));
                _store.Activate(candidate);
            }
            finally { try { File.Delete(temp); } catch { } }
            Changed($"Downloaded, verified, installed and activated {entry.Name}.");
        }
        catch (Exception ex) { Error(ex); }
    }

    private static List<OfficialRendererEntry> ParseCatalog(byte[] bytes)
    {
        using var doc = JsonDocument.Parse(bytes); var root = doc.RootElement;
        if (root.Int("schema", 0) != 1) throw new InvalidDataException("Unsupported renderer repository schema.");
        if (root.String("publisher", "") != "RetroFrost" || root.String("repository", "") != "RetroFrost/CTS") throw new InvalidDataException("Renderer repository identity is not trusted.");
        if (!root.TryGetProperty("renderers", out var array) || array.ValueKind != JsonValueKind.Array) throw new InvalidDataException("Renderer repository has no entries.");
        var ids = new HashSet<string>(StringComparer.Ordinal); var result = new List<OfficialRendererEntry>();
        foreach (var item in array.EnumerateArray())
        {
            var entry = new OfficialRendererEntry(item.String("id", "").Trim(), item.String("name", "").Trim(), item.String("description", "").Trim(), item.String("author", "RetroFrost").Trim(), item.String("url", "").Trim(), item.String("sha256", "").Trim().ToLowerInvariant(), item.String("minAppVersion", "2.0.8").Trim());
            if (entry.Id.Length == 0 || entry.Name.Length == 0 || !ids.Add(entry.Id)) throw new InvalidDataException("Renderer repository contains an invalid or duplicate entry.");
            RequireOfficialUrl(entry.Url);
            if (entry.Sha256.Length != 64 || entry.Sha256.Any(c => !Uri.IsHexDigit(c))) throw new InvalidDataException("Renderer repository contains an invalid checksum.");
            result.Add(entry);
        }
        return result;
    }

    private static async Task<byte[]> DownloadLimitedAsync(string url, int limit)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url); request.Headers.UserAgent.ParseAdd("Cubical-Compare/3.0.300-Windows");
        using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead); response.EnsureSuccessStatusCode();
        if (response.Content.Headers.ContentLength is long announced && announced > limit) throw new InvalidDataException("Download is larger than the allowed size.");
        await using var input = await response.Content.ReadAsStreamAsync(); using var output = new MemoryStream(); var buffer = new byte[16 * 1024];
        while (true) { var read = await input.ReadAsync(buffer); if (read <= 0) break; if (output.Length + read > limit) throw new InvalidDataException("Download is larger than the allowed size."); output.Write(buffer, 0, read); }
        return output.ToArray();
    }

    private static void RequireOfficialUrl(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps || !uri.Host.Equals("raw.githubusercontent.com", StringComparison.OrdinalIgnoreCase) || !value.StartsWith(OfficialPrefix, StringComparison.Ordinal))
            throw new InvalidDataException("Renderer URL is outside the trusted official repository.");
    }

    private void Changed(string text) { _message.Text = text; RefreshInstalled(); _onChanged(); }
    private void Error(Exception error) { _message.Text = error.Message; MessageBox.Show(this, error.Message, "Renderer library", MessageBoxButton.OK, MessageBoxImage.Error); }
    private static StackPanel Card() => new() { Margin = new Thickness(0), Orientation = Orientation.Vertical };
    private static TextBlock Info(string text) => new() { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = new SolidColorBrush(Color.FromRgb(190,190,190)), Margin = new Thickness(0, 3, 0, 0) };
    private static Button Button(string text, Action action) { var b = new Button { Content = text, Margin = new Thickness(0, 5, 7, 0), Padding = new Thickness(10, 5, 10, 5) }; b.Click += (_, _) => action(); return b; }
    private static Button Button(string text, Func<Task> action) { var b = new Button { Content = text, Margin = new Thickness(0, 5, 7, 0), Padding = new Thickness(10, 5, 10, 5) }; b.Click += async (_, _) => { b.IsEnabled = false; try { await action(); } finally { b.IsEnabled = true; } }; return b; }
}