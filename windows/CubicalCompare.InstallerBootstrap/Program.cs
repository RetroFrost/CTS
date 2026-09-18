using System.Diagnostics;
using System.Drawing;
using System.Reflection;
using System.Windows.Forms;

namespace CubicalCompare.InstallerBootstrap;

internal static class Program
{
    private const string ResourceName = "CubicalCompare.Velopack.Setup.exe";

    [STAThread]
    public static int Main()
    {
        ApplicationConfiguration.Initialize();
        using var window = new InstallerWindow();
        Application.Run(window);
        return window.ExitCode;
    }

    private sealed class InstallerWindow : Form
    {
        private readonly Label _status;
        private readonly ProgressBar _progress;
        private bool _allowClose;

        public InstallerWindow()
        {
            Text = "Cubical Compare Setup";
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = true;
            ClientSize = new Size(500, 150);
            AutoScaleMode = AutoScaleMode.Dpi;

            var title = new Label
            {
                AutoSize = true,
                Text = "Installing Cubical Compare",
                Font = new Font(SystemFonts.MessageBoxFont.FontFamily, 15, FontStyle.Bold),
                Location = new Point(24, 20),
            };

            _status = new Label
            {
                AutoSize = false,
                Text = "Preparing installer…",
                Location = new Point(26, 60),
                Size = new Size(448, 24),
            };

            _progress = new ProgressBar
            {
                Location = new Point(26, 94),
                Size = new Size(448, 18),
                Style = ProgressBarStyle.Marquee,
                MarqueeAnimationSpeed = 24,
            };

            Controls.Add(title);
            Controls.Add(_status);
            Controls.Add(_progress);
        }

        public int ExitCode { get; private set; } = 1;

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            _ = InstallAsync();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!_allowClose && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                return;
            }

            base.OnFormClosing(e);
        }

        private async Task InstallAsync()
        {
            try
            {
                _status.Text = "Installing files…";
                var exitCode = await Task.Run(RunEmbeddedInstaller);
                if (exitCode != 0)
                    throw new InvalidOperationException($"The installer engine exited with code {exitCode}.");

                var installedExe = FindInstalledExecutable()
                    ?? throw new FileNotFoundException("Cubical Compare was installed, but CubicalCompare.exe could not be found.");

                _status.Text = "Starting Cubical Compare…";
                _ = Process.Start(new ProcessStartInfo
                {
                    FileName = installedExe,
                    WorkingDirectory = Path.GetDirectoryName(installedExe)!,
                    UseShellExecute = true,
                }) ?? throw new InvalidOperationException("Windows could not start Cubical Compare after installation.");

                ExitCode = 0;
                _allowClose = true;
                await Task.Delay(600);
                Close();
            }
            catch (Exception ex)
            {
                WriteInstallerError(ex);
                ShowFailure(ex);
            }
        }

        private static int RunEmbeddedInstaller()
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

                // The public Setup window above remains visible throughout installation.
                // The embedded engine is unattended only to avoid a second installer UI
                // and the old final acknowledgement dialog.
                var process = Process.Start(new ProcessStartInfo
                {
                    FileName = setupPath,
                    Arguments = "--silent",
                    UseShellExecute = true,
                    WorkingDirectory = root,
                }) ?? throw new InvalidOperationException("Windows could not start the Cubical Compare installer engine.");

                process.WaitForExit();
                return process.ExitCode;
            }
            finally
            {
                try { Directory.Delete(root, recursive: true); } catch { }
            }
        }

        private static string? FindInstalledExecutable()
        {
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var expected = Path.Combine(localAppData, "CubicalCompare", "current", "CubicalCompare.exe");
            if (File.Exists(expected))
                return expected;

            var root = Path.Combine(localAppData, "CubicalCompare");
            if (!Directory.Exists(root))
                return null;

            return Directory.EnumerateFiles(root, "CubicalCompare.exe", SearchOption.AllDirectories)
                .FirstOrDefault();
        }

        private void ShowFailure(Exception ex)
        {
            _progress.Style = ProgressBarStyle.Blocks;
            _progress.Value = 0;
            _status.Text = "Installation failed. Details were saved to the installer log.";

            var close = new Button
            {
                Text = "Close",
                AutoSize = true,
                Location = new Point(399, 118),
            };
            close.Click += (_, _) =>
            {
                _allowClose = true;
                Close();
            };
            Controls.Add(close);

            var toolTip = new ToolTip();
            toolTip.SetToolTip(_status, ex.Message);
        }

        private static void WriteInstallerError(Exception ex)
        {
            try
            {
                var directory = Path.Combine(Path.GetTempPath(), "CubicalCompare");
                Directory.CreateDirectory(directory);
                File.WriteAllText(Path.Combine(directory, "installer-error.txt"), ex.ToString());
            }
            catch
            {
            }
        }
    }
}
