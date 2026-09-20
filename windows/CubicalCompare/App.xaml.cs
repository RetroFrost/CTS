using System.Text;
using CubicalCompare.Core.Project;
using CubicalCompare.Core.Renderer;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public partial class App : Application
{
    public static Window? MainWindow { get; private set; }
    public static string LogPath { get; } = Path.Combine(AppDataPaths.RootDirectory, "startup.log");

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
        WriteLog($"Renderer compatibility app version: {LegacyRendererAdapter.RuntimeCompatibilityAppVersion}.");
        try
        {
            if (string.Equals(args.Arguments, "--ci-shell-smoke", StringComparison.Ordinal))
            {
                InternalCodeOverrideManager.RunCompilerSelfTest();
                WriteLog("Internal code override compiler self-test passed.");
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

            mainWindow.DispatcherQueue.TryEnqueue(() =>
            {
                try
                {
                    // Artwork drag/resize/rotate now lives directly on the full rendered
                    // preview. Keep the inspector's numeric precision controls, but do not
                    // create the old isolated 471×872 mini-canvas that hid surrounding cards.
                    mainWindow.InitializeSoundtrackEditor();
                    mainWindow.InitializeFontSelector();
                    mainWindow.InitializeReliablePreviewTransformEditor();
                    mainWindow.InitializePreviewPlayback();
                    mainWindow.InitializePersonalization();
                    mainWindow.InitializeFinalReleaseUi();
                    mainWindow.InitializeAdaptiveExportUi();
                    mainWindow.InitializeInterfaceReview();
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
