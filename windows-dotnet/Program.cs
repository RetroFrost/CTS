using System.Windows;

namespace CubicalCompare.Windows;

public static class Program
{
    [STAThread]
    public static void Main()
    {
        var app = new Application
        {
            ShutdownMode = ShutdownMode.OnMainWindowClose,
        };
        app.DispatcherUnhandledException += (_, e) =>
        {
            try
            {
                var report = $"Cubical Compare 3.0.300 Windows\r\n{DateTimeOffset.Now:O}\r\n\r\n{e.Exception}";
                Clipboard.SetText(report);
                MessageBox.Show("Cubical Compare hit an unexpected error. The crash report was copied to the clipboard.\n\n" + e.Exception.Message, "Cubical Compare", MessageBoxButton.OK, MessageBoxImage.Error);
            }
            catch { }
            e.Handled = true;
        };

        var mainWindow = new MainWindow();
        AdaptiveLayout.Attach(mainWindow);
        app.Run(mainWindow);
    }
}
