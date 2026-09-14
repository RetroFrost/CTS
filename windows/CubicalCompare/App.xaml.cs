using System.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public partial class App : Application
{
    public static Window? MainWindow { get; private set; }
    public static string LogPath { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "CubicalCompare",
        "startup.log");

    public App()
    {
        EnsureLogDirectory();
        WriteLog("Process entered App constructor.");

        UnhandledException += (_, args) => WriteLog("WinUI unhandled exception", args.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, args) => WriteLog("AppDomain unhandled exception", args.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, args) => WriteLog("Unobserved task exception", args.Exception);

        InitializeComponent();
        WriteLog("App XAML initialised.");
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        WriteLog($"OnLaunched entered. Arguments: '{args.Arguments}'.");
        try
        {
            if (string.Equals(args.Arguments, "--ci-shell-smoke", StringComparison.Ordinal))
            {
                MainWindow = new Window
                {
                    Title = "Cubical Compare — WinUI shell smoke",
                    Content = new Grid
                    {
                        Children =
                        {
                            new TextBlock
                            {
                                Text = "Cubical Compare WinUI shell is alive.",
                                HorizontalAlignment = HorizontalAlignment.Center,
                                VerticalAlignment = VerticalAlignment.Center,
                            },
                        },
                    },
                };
                WriteLog("CI shell smoke window constructed.");
                MainWindow.Activate();
                WriteLog("CI shell smoke window activated.");
                return;
            }

            var mainWindow = new MainWindow();
            MainWindow = mainWindow;
            WriteLog("MainWindow constructed.");
            mainWindow.Activate();
            WriteLog("MainWindow activated.");

            // Controls that need to inspect or extend the realised visual tree are deliberately
            // installed after activation. Doing this in the constructor made their ancestors
            // unreliable and was the reason direct transform occasionally appeared but did nothing.
            mainWindow.DispatcherQueue.TryEnqueue(() =>
            {
                try
                {
                    mainWindow.InitializeDirectArtworkManipulator();
                    mainWindow.InitializeSoundtrackEditor();
                    mainWindow.InitializeFontSelector();
                    WriteLog("Post-activation editor controls initialised.");
                }
                catch (Exception ex)
                {
                    WriteLog("Post-activation editor control initialisation failed", ex);
                }
            });
        }
        catch (Exception ex)
        {
            WriteLog("MainWindow startup failed", ex);
            ShowRecoveryWindow(ex);
        }
    }

    private static void ShowRecoveryWindow(Exception exception)
    {
        try
        {
            var panel = new StackPanel
            {
                Spacing = 12,
                Padding = new Thickness(24),
            };
            panel.Children.Add(new TextBlock
            {
                Text = "Cubical Compare could not finish starting.",
                FontSize = 24,
                TextWrapping = TextWrapping.Wrap,
            });
            panel.Children.Add(new TextBlock
            {
                Text = exception.ToString(),
                TextWrapping = TextWrapping.Wrap,
                IsTextSelectionEnabled = true,
            });
            panel.Children.Add(new TextBlock
            {
                Text = $"Startup log: {LogPath}",
                TextWrapping = TextWrapping.Wrap,
                IsTextSelectionEnabled = true,
            });

            MainWindow = new Window
            {
                Title = "Cubical Compare — startup recovery",
                Content = new ScrollViewer { Content = panel },
            };
            MainWindow.Activate();
        }
        catch (Exception recoveryException)
        {
            WriteLog("Recovery window also failed", recoveryException);
        }
    }

    private static void EnsureLogDirectory()
    {
        var directory = Path.GetDirectoryName(LogPath);
        if (!string.IsNullOrWhiteSpace(directory)) Directory.CreateDirectory(directory);
    }

    public static void WriteLog(string message, Exception? exception = null)
    {
        try
        {
            EnsureLogDirectory();
            var text = new StringBuilder()
                .Append('[').Append(DateTimeOffset.Now.ToString("O")).Append("] ")
                .AppendLine(message);
            if (exception is not null) text.AppendLine(exception.ToString());
            File.AppendAllText(LogPath, text.ToString());
        }
        catch
        {
            // Diagnostics must never become a second application failure.
        }
    }
}
