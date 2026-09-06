using System.IO.Compression;
using System.Text;

namespace CubicalCompare.Windows;

public static class AppSelfTest
{
    public static int Run()
    {
        var root = Path.Combine(Path.GetTempPath(), "CubicalCompare-selftest-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            ProjectRoundTrip();
            SpreadsheetImport(root);
            MegaPackImport(root);
            RendererAndRaster();
            if (RendererCapabilities.CompareVersions("3.0.300", "2.0.8") < 0) throw new InvalidOperationException("Semantic version comparison regressed.");
            return 0;
        }
        catch { return 1; }
        finally { try { Directory.Delete(root, true); } catch { } }
    }

    private static void ProjectRoundTrip()
    {
        var project = new StudioProject
        {
            Name = "Round trip",
            IntroMode = IntroMode.Disabled,
            SoundtrackVolume = .42f,
            SoundtrackLoop = false,
            EncoderPreference = EncoderPreference.H265,
            FontFamily = "serif",
            Cards = [new StudioCard { Title = "Alpha", Value = "42 ms", BadgeHeader = "1 IN", Description = "Description", ImageX = 12.5, ImageY = -9, ImageScale = 1.7, ImageRotation = 11, ImageCropLeft = .1, ImageLayer = "front" }],
        };
        var loaded = StudioProject.FromJson(project.ToJson());
        if (loaded.Name != project.Name || loaded.Cards.Count != 1 || loaded.Cards[0].Title != "Alpha" || Math.Abs(loaded.Cards[0].ImageScale - 1.7) > .0001 || loaded.Cards[0].ImageLayer != "front" || loaded.EncoderPreference != EncoderPreference.H265) throw new InvalidOperationException("Project JSON round-trip failed.");
    }

    private static void SpreadsheetImport(string root)
    {
        var path = Path.Combine(root, "cards.csv");
        File.WriteAllText(path, "title,value,badge_header,description\n\"A, quoted\",10,1 IN,First\nB,20,,Second\n", Encoding.UTF8);
        var project = NativeImporters.ImportData(new StudioProject(), path);
        if (project.Cards.Count != 2 || project.Cards[0].Title != "A, quoted" || project.Cards[1].Value != "20") throw new InvalidOperationException("CSV importer self-test failed.");
    }

    private static void MegaPackImport(string root)
    {
        var path = Path.Combine(root, "pack.zip");
        using (var zip = ZipFile.Open(path, ZipArchiveMode.Create))
        {
            var manifest = zip.CreateEntry("megapack.json");
            using var writer = new StreamWriter(manifest.Open(), Encoding.UTF8);
            writer.Write("{\"version\":2,\"name\":\"Pack\",\"cards\":[{\"title\":\"One\",\"value\":\"1\"},{\"title\":\"Two\",\"badge_primary\":\"2\",\"badge_secondary\":\"days\"}]} ");
        }
        var assets = Path.Combine(root, "pack-assets");
        var project = NativeImporters.ImportMegaPack(path, assets);
        if (project.Cards.Count != 2 || project.Cards[1].Value != "2 days") throw new InvalidOperationException("MegaPack importer self-test failed.");
    }

    private static void RendererAndRaster()
    {
        var project = new StudioProject { Cards = [new StudioCard { Title = "Render", Value = "1", Description = "Test" }] };
        var spec = RendererSpec.BuiltIn();
        using var engine = new RendererEngine();
        using var bitmap = engine.Render(project, spec, 0, 320, 180);
        if (bitmap.Width != 320 || bitmap.Height != 180 || bitmap.ByteCount <= 0) throw new InvalidOperationException("Renderer raster self-test failed.");
    }
}