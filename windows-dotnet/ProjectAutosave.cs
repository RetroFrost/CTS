namespace CubicalCompare.Windows;

public static class ProjectAutosave
{
    private static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CubicalCompare");
    private static readonly string AutosavePath = Path.Combine(Root, "autosave.json");
    private static readonly string BackupPath = Path.Combine(Root, "autosave.backup.json");

    public static string Location => AutosavePath;

    public static StudioProject? Load()
    {
        foreach (var path in new[] { AutosavePath, BackupPath })
        {
            try
            {
                if (!File.Exists(path)) continue;
                var project = StudioProject.FromJson(File.ReadAllText(path));
                if (project.Cards.Count > 0) return project;
            }
            catch { }
        }
        return null;
    }

    public static void Save(StudioProject project)
    {
        Directory.CreateDirectory(Root);
        var temp = AutosavePath + ".tmp";
        File.WriteAllText(temp, project.ToJson());
        using (var stream = new FileStream(temp, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) stream.Flush(true);
        if (File.Exists(AutosavePath))
        {
            try { File.Copy(AutosavePath, BackupPath, true); } catch { }
        }
        File.Move(temp, AutosavePath, true);
    }

    public static void Clear()
    {
        foreach (var path in new[] { AutosavePath, BackupPath, AutosavePath + ".tmp" })
            try { if (File.Exists(path)) File.Delete(path); } catch { }
    }
}