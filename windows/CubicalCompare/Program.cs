using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Velopack;

namespace CubicalCompare;

public static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        // Velopack must see install/update hook arguments before WinUI constructs App.
        VelopackApp.Build()
            .SetAutoApplyOnStartup(false)
            .Run();

        WinRT.ComWrappersSupport.InitializeComWrappers();
        Application.Start(initializationParams =>
        {
            _ = initializationParams; // WinUI owns the callback payload; the app only needs the UI thread.
            var context = new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread());
            SynchronizationContext.SetSynchronizationContext(context);
            new App();
        });
        return 0;
    }
}
