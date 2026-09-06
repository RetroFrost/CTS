using System.Text;
using System.Windows;

namespace CubicalCompare.Windows;

public static class CrashReporter
{
    private static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CubicalCompare", "crashes");
    private static int _reporting;

    public static void Install(Application app)
    {
        app.DispatcherUnhandledException += (_, e) =>
        {
            Report(e.Exception, "WPF dispatcher", showDialog: true);
            e.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Report(e.ExceptionObject as Exception ?? new Exception(e.ExceptionObject?.ToString() ?? "Unknown fatal error"), "AppDomain", showDialog: false);
        TaskScheduler.UnobservedTaskException += (_, e) => { Report(e.Exception, "TaskScheduler", showDialog: false); e.SetObserved(); };
    }

    public static string Report(Exception error, string source, bool showDialog)
    {
        if (Interlocked.Exchange(ref _reporting, 1) != 0) return "";
        try
        {
            Directory.CreateDirectory(Root);
            var timestamp = DateTimeOffset.Now;
            var report = new StringBuilder()
                .AppendLine("Cubical Compare 3.0.300 Windows crash report")
                .AppendLine($"Time: {timestamp:O}")
                .AppendLine($"Source: {source}")
                .AppendLine($"OS: {Environment.OSVersion}")
                .AppendLine($"64-bit process: {Environment.Is64BitProcess}")
                .AppendLine($".NET: {Environment.Version}")
                .AppendLine()
                .AppendLine(error.ToString())
                .ToString();
            var path = Path.Combine(Root, $"crash-{timestamp:yyyyMMdd-HHmmss-fff}.txt");
            try { File.WriteAllText(path, report); } catch { path = ""; }
            try { if (Application.Current?.Dispatcher?.CheckAccess() == true) Clipboard.SetText(report); else Application.Current?.Dispatcher?.Invoke(() => Clipboard.SetText(report)); } catch { }
            if (showDialog)
            {
                try { MessageBox.Show("Cubical Compare hit an unexpected error. The crash report was saved and copied to the clipboard.\n\n" + error.Message + (path.Length > 0 ? $"\n\n{path}" : ""), "Cubical Compare", MessageBoxButton.OK, MessageBoxImage.Error); } catch { }
            }
            return path;
        }
        finally { Volatile.Write(ref _reporting, 0); }
    }
}