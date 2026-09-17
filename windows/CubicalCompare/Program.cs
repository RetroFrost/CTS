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
        Application.Start(_ =>
        {
            var context = new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread());
            SynchronizationContext.SetSynchronizationContext(context);
            _ = new App();
        });
        return 0;
    }
}
