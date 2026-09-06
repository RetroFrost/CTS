using System.Windows;

namespace CubicalCompare.Windows;

public static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Any(arg => arg.Equals("--self-test", StringComparison.OrdinalIgnoreCase))) return AppSelfTest.Run();

        if (args.FirstOrDefault() == "--render-frames") return RendererFrameCommand.Run(args);

        var app = new Application
        {
            ShutdownMode = ShutdownMode.OnMainWindowClose,
        };
        CrashReporter.Install(app);
        return app.Run(new MainWindow());
    }
}
