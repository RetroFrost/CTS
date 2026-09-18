using System.Diagnostics;
using System.Reflection;

namespace CubicalCompare.InstallerBootstrap;

internal static class Program
{
    private const string ResourceName = "CubicalCompare.Velopack.Setup.exe";

    [STAThread]
    public static int Main()
    {
        var root = Path.Combine(Path.GetTempPath(), "CubicalCompare", "Installer", Guid.NewGuid().ToString("N"));
        var setupPath = Path.Combine(root, "CubicalCompare-Velopack-Setup.exe");

        try
        {
            Directory.CreateDirectory(root);
            using var input = Assembly.GetExecutingAssembly().GetManifestResourceStream(ResourceName)
                ?? throw new InvalidOperationException("The embedded Cubical Compare installer payload is missing.");
            using (var output = new FileStream(setupPath, FileMode.CreateNew, FileAccess.Write, FileShare.Read))
                input.CopyTo(output);

            // Public Cubical Compare installers are always interactive by default.
            // Do not pass --silent here: Velopack's normal Setup UI shows installation
            // progress and launches the app when installation completes.
            var process = Process.Start(new ProcessStartInfo
            {
                FileName = setupPath,
                UseShellExecute = true,
                WorkingDirectory = root,
            }) ?? throw new InvalidOperationException("Windows could not start the Cubical Compare installer.");

            process.WaitForExit();
            return process.ExitCode;
        }
        catch (Exception ex)
        {
            try
            {
                Directory.CreateDirectory(Path.Combine(Path.GetTempPath(), "CubicalCompare"));
                File.WriteAllText(Path.Combine(Path.GetTempPath(), "CubicalCompare", "installer-error.txt"), ex.ToString());
            }
            catch
            {
            }
            return 1;
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }
}
