using CubicalCompare.Core.Project.Patching;
using System.IO.Compression;
using System.Text;

var root = Path.Combine(Path.GetTempPath(), "CTS-patch-tests-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
var passed = 0;
void Equal<T>(T actual, T expected) { if (!Equals(actual, expected)) throw new Exception($"Expected {expected}, got {actual}"); }
string Diff(string old, string next, string hunk) => $"diff --git a/{old} b/{next}\n--- {(old=="/dev/null" ? old : "a/"+old)}\n+++ {(next=="/dev/null" ? next : "b/"+next)}\n{hunk}";
void Check(string before, string patch, string after)
{
    File.WriteAllText(Path.Combine(root, "test.txt"), before, new UTF8Encoding(false));
    var changes = UnifiedPatch.Prepare(root, patch);
    Equal(File.ReadAllText(Path.Combine(root, "test.txt")), before);
    UnifiedPatch.Apply(root, changes);
    Equal(File.ReadAllText(Path.Combine(root, "test.txt")), after);
    passed++;
}
void Rejected(string patch)
{
    File.WriteAllText(Path.Combine(root, "test.txt"), "unchanged\n");
    try { UnifiedPatch.Prepare(root, patch); throw new Exception("Invalid patch accepted"); }
    catch (InvalidDataException) { }
    Equal(File.ReadAllText(Path.Combine(root, "test.txt")), "unchanged\n");
    passed++;
}
try
{
    Check("old\n", Diff("test.txt","test.txt","@@ -1 +1 @@\n-old\n+new\n"), "new\n");
    Check("a\nb\nc\nd\n", Diff("test.txt","test.txt","@@ -1 +1 @@\n-a\n+A\n@@ -4 +4 @@\n-d\n+D\n"), "A\nb\nc\nD\n");
    Check("a\nb\n", Diff("test.txt","test.txt","@@ -1,0 +2 @@\n+insert\n"), "a\ninsert\nb\n");
    Check("a\r\nb\r\n", Diff("test.txt","test.txt","@@ -1,2 +1,2 @@\n a\n-b\n+B\n"), "a\r\nB\r\n");
    Check("a\nb", Diff("test.txt","test.txt","@@ -1,2 +1 @@\n a\n-b\n\\ No newline at end of file\n"), "a\n");
    Check("a\n", Diff("test.txt","test.txt","@@ -1 +1 @@\n-a\n+A\n\\ No newline at end of file\n"), "A");
    Check("a", Diff("test.txt","test.txt","@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+A\n"), "A\n");
    File.WriteAllText(Path.Combine(root,"test.txt"),"bom\r\n",new UTF8Encoding(true));
    UnifiedPatch.Apply(root,UnifiedPatch.Prepare(root,Diff("test.txt","test.txt","@@ -1 +1 @@\n-bom\n+BOM\n")));
    Equal(File.ReadAllBytes(Path.Combine(root,"test.txt")).Take(3).SequenceEqual(new byte[]{239,187,191}),true);Equal(File.ReadAllText(Path.Combine(root,"test.txt")),"BOM\r\n");passed++;
    var patch=Diff("/dev/null","added.txt","@@ -0,0 +1 @@\n+added\n");
    UnifiedPatch.Apply(root,UnifiedPatch.Prepare(root,patch));Equal(File.ReadAllText(Path.Combine(root,"added.txt")),"added\n");passed++;
    patch=Diff("added.txt","/dev/null","@@ -1 +0,0 @@\n-added\n");
    UnifiedPatch.Apply(root,UnifiedPatch.Prepare(root,patch));Equal(File.Exists(Path.Combine(root,"added.txt")),false);passed++;
    File.WriteAllText(Path.Combine(root,"test.txt"),"rename\n");
    patch=Diff("test.txt","renamed.txt","@@ -1 +1 @@\n-rename\n+renamed\n");
    UnifiedPatch.Apply(root,UnifiedPatch.Prepare(root,patch));Equal(File.Exists(Path.Combine(root,"test.txt")),false);Equal(File.ReadAllText(Path.Combine(root,"renamed.txt")),"renamed\n");passed++;
    foreach(var path in new[]{"../escape.cs","/absolute.cs","C:/escape.cs",".git/config","bad:stream","CON.txt","trailing.","bad*.cs"})
        Rejected(Diff("/dev/null",path,"@@ -0,0 +1 @@\n+unsafe\n"));
    Rejected(Diff("test.txt","test.txt","@@ -1 +1 @@\n-other\n+new\n"));
    Rejected(Diff("test.txt","test.txt","@@ -1,2 +1 @@\n unchanged\n"));
    Rejected(Diff("test.txt","test.txt","@@ -1 +1 @@\n-unchanged\n+new\n")+"GIT binary patch\n");
    Rejected(Diff("test.txt","test.txt","@@ -1 +1 @@\n-unchanged\n+new\n")+Diff("test.txt","test.txt","@@ -1 +1 @@\n-unchanged\n+new\n"));
    // Late failure must not apply earlier files.
    Rejected(Diff("test.txt","test.txt","@@ -1 +1 @@\n-unchanged\n+new\n")+Diff("missing.txt","missing.txt","@@ -1 +1 @@\n-absent\n+new\n"));
    if (!OperatingSystem.IsWindows())
    {
        var linked=Path.Combine(root,"linked");Directory.CreateSymbolicLink(linked,Path.GetTempPath());
        Rejected(Diff("/dev/null","linked/escape.cs","@@ -0,0 +1 @@\n+unsafe\n"));Directory.Delete(linked);
    }
    var source = Path.Combine(root,"snapshot");Directory.CreateDirectory(Path.Combine(source,"windows","CubicalCompare"));
    File.WriteAllText(Path.Combine(source,"windows","CubicalCompare","CubicalCompare.csproj"),"<Project/>\n");
    File.WriteAllText(Path.Combine(source,"test.txt"),"one\n");
    var zip=Path.Combine(root,"source.zip");ZipFile.CreateFromDirectory(source,zip);
    var first=Path.Combine(root,"first.patch");var second=Path.Combine(root,"second.patch");
    File.WriteAllText(first,Diff("test.txt","test.txt","@@ -1 +1 @@\n-one\n+two\n"));
    File.WriteAllText(second,Diff("test.txt","test.txt","@@ -1 +1 @@\n-two\n+three\n"));
    using(var session=await AppPatchBuilder.PrepareAsync(zip,[first,second],Path.Combine(root,"jobs"))) {
        Equal(File.ReadAllText(Path.Combine(session.SourceDirectory,"test.txt")),"three\n");
        Equal(File.ReadAllText(Path.Combine(source,"test.txt")),"one\n");
        Equal(session.ChangedFiles.Count,1);passed++;
    }
    using(var token=new CancellationTokenSource()) { token.Cancel();try {await AppPatchBuilder.PrepareAsync(zip,[first],Path.Combine(root,"jobs"),token.Token);throw new Exception("Cancellation ignored");}catch(OperationCanceledException){passed++;} }
    var malicious=Path.Combine(root,"malicious.zip");using(var archive=ZipFile.Open(malicious,ZipArchiveMode.Create)){using var writer=new StreamWriter(archive.CreateEntry("../outside.txt").Open());writer.Write("unsafe");}
    try{await AppPatchBuilder.PrepareAsync(malicious,[first],Path.Combine(root,"jobs"));throw new Exception("Unsafe snapshot accepted");}catch(InvalidDataException){passed++;}
    if (args.Length == 4 && args[0] == "--check-repo")
    {
        var changes=UnifiedPatch.Prepare(args[1],File.ReadAllText(args[2]));
        UnifiedPatch.Apply(args[1],changes);
        foreach(var change in changes)
        {
            if(change.NewPath is null){if(File.Exists(UnifiedPatch.Resolve(args[1],change.OldPath!)))throw new Exception("Delete was not applied");continue;}
            if(!File.ReadAllBytes(UnifiedPatch.Resolve(args[1],change.NewPath)).SequenceEqual(File.ReadAllBytes(UnifiedPatch.Resolve(args[3],change.NewPath))))throw new Exception("Patch bytes differ: "+change.NewPath);
        }
        Console.WriteLine($"PASS actual release patch: {changes.Count} files reproduce the expected repository bytes.");
    }
    if (args.Length == 2 && args[0] == "--full-build")
    {
        var xamlPath="windows/CubicalCompare/MainWindow.xaml";
        var sourceLine="                                <TextBlock Text=\"Developer\" FontSize=\"28\" FontWeight=\"SemiBold\" />";
        using(var archive=ZipFile.OpenRead(args[1]))
        {
            using var reader=new StreamReader(archive.GetEntry(xamlPath)!.Open());
            sourceLine=reader.ReadToEnd().Replace("\r\n","\n").Split('\n').Single(line=>line.Contains("Text=\"Developer\" FontSize=\"28\""));
        }
        var fullPatch=Path.Combine(root,"full.patch");
        // Locate the exact line from the bundled snapshot, not a guessed version-specific offset.
        int number;
        using(var archive=ZipFile.OpenRead(args[1])) {using var reader=new StreamReader(archive.GetEntry(xamlPath)!.Open());number=Array.IndexOf(reader.ReadToEnd().Replace("\r\n","\n").Split('\n'),sourceLine)+1;}
        File.WriteAllText(fullPatch,Diff(xamlPath,xamlPath,$"@@ -{number} +{number} @@\n-{sourceLine}\n+{sourceLine.Replace("Text=\"Developer\"","Text=\"Developer patch smoke\"")}\n"));
        using var session=await AppPatchBuilder.PrepareAsync(args[1],[fullPatch],Path.Combine(root,"full-jobs"));
        var built=await session.BuildAsync(new Progress<string>(Console.WriteLine));
        using var result=ZipFile.OpenRead(built);
        if(!result.Entries.Any(x=>x.FullName=="CubicalCompare.exe"))throw new Exception("Full app executable missing");
        using var bundled=result.GetEntry("Assets/AppSource.zip")!.Open();
        using var snapshot=new ZipArchive(bundled);
        using var text=new StreamReader(snapshot.GetEntry(xamlPath)!.Open());
        if(!text.ReadToEnd().Contains("Developer patch smoke"))throw new Exception("Patched source was not bundled");
        Console.WriteLine("PASS full-app XAML patch built and repackaged successfully.");
    }
    var ignored=new List<string>();
    var metadata=Diff(".github/workflows/build.yml",".github/workflows/build.yml","@@ -1 +1 @@\n-old-release\n+new-release\n");
    File.WriteAllText(Path.Combine(root,"test.txt"),"old\n");
    var filtered=UnifiedPatch.Prepare(root,metadata+Diff("test.txt","test.txt","@@ -1 +1 @@\n-old\n+new\n"),
        (oldPath,newPath)=>(oldPath is null || !AppPatchBuilder.IsRepositoryMetadata(oldPath)) && (newPath is null || !AppPatchBuilder.IsRepositoryMetadata(newPath)),ignored);
    Equal(filtered.Count,1);Equal(ignored.Count,1);UnifiedPatch.Apply(root,filtered);Equal(File.ReadAllText(Path.Combine(root,"test.txt")),"new\n");passed++;
    Equal(AppPatchBuilder.IsRepositoryMetadata("Directory.Build.targets"),false);Equal(AppPatchBuilder.IsRepositoryMetadata("windows/CubicalCompare/MainWindow.xaml"),false);passed++;
    var skippedHeader=new List<string>();
    filtered=UnifiedPatch.Prepare(root,Diff("docs/guide.md","docs/guide.md","@@ -1 +1 @@\n--- old text\n+new text\n"),(a,b)=>false,skippedHeader);
    Equal(filtered.Count,0);Equal(skippedHeader.Count,1);passed++;
    try {UnifiedPatch.Prepare(root,metadata+Diff("test.txt","test.txt","@@ -1 +1 @@\n-wrong-runtime-context\n+bad\n"),(a,b)=>(b is null || !AppPatchBuilder.IsRepositoryMetadata(b)),new List<string>());throw new Exception("Runtime mismatch accepted");}catch(InvalidDataException){passed++;}
    Console.WriteLine("PASS repository metadata filtering; real app code context remains strict.");
    var repeated = new List<string>();
    File.WriteAllText(Path.Combine(root,"created.cs"),"same\n");
    filtered = UnifiedPatch.Prepare(root, Diff("/dev/null","created.cs","@@ -0,0 +1 @@\n+same\n"), alreadyAppliedFiles: repeated);
    Equal(filtered.Count,0);Equal(repeated.Single(),"created.cs");passed++;
    File.WriteAllText(Path.Combine(root,"created.cs"),"same\r\n",new UTF8Encoding(true));
    filtered = UnifiedPatch.Prepare(root, Diff("/dev/null","created.cs","@@ -0,0 +1 @@\n+same\n"), alreadyAppliedFiles: new List<string>());
    Equal(filtered.Count,0);Equal(File.ReadAllText(Path.Combine(root,"created.cs")),"same\r\n");passed++;
    File.WriteAllText(Path.Combine(root,"test.txt"),"new\n");
    filtered=UnifiedPatch.Prepare(root,Diff("test.txt","test.txt","@@ -1 +1 @@\n-old\n+new\n"),alreadyAppliedFiles:new List<string>());
    Equal(filtered.Count,0);Equal(File.ReadAllText(Path.Combine(root,"test.txt")),"new\n");passed++;
    File.WriteAllText(Path.Combine(root,"test.txt"),"A\nb\nC\n");
    filtered=UnifiedPatch.Prepare(root,Diff("test.txt","test.txt","@@ -1 +1 @@\n-a\n+A\n@@ -3 +3 @@\n-c\n+C\n"),alreadyAppliedFiles:new List<string>());
    Equal(filtered.Count,0);passed++;
    File.WriteAllText(Path.Combine(root,"test.txt"),"new");
    filtered=UnifiedPatch.Prepare(root,Diff("test.txt","test.txt","@@ -1 +1 @@\n-old\n+new\n\\ No newline at end of file\n"),alreadyAppliedFiles:new List<string>());
    Equal(filtered.Count,0);passed++;
    foreach (var conflict in new[] {
        Diff("/dev/null","created.cs","@@ -0,0 +1 @@\n+different\n"),
        Diff("test.txt","test.txt","@@ -1 +1 @@\n-other\n+something\n"),
        Diff("test.txt","test.txt","@@ -1 +1 @@\n-old\n+unchanged\n\\ No newline at end of file\n"),
        Diff("test.txt","test.txt","@@ -1 +1 @@\n-unchanged\n+new\n") + Diff("/dev/null","created.cs","@@ -0,0 +1 @@\n+conflict\n")
    }) Rejected(conflict);
    // Already applied files must not stop remaining new work in a mixed patch.
    File.WriteAllText(Path.Combine(root,"test.txt"),"old\n");
    filtered=UnifiedPatch.Prepare(root,Diff("/dev/null","created.cs","@@ -0,0 +1 @@\n+same\n")+Diff("test.txt","test.txt","@@ -1 +1 @@\n-old\n+new\n"),alreadyAppliedFiles:new List<string>());
    Equal(filtered.Count,1);UnifiedPatch.Apply(root,filtered);Equal(File.ReadAllText(Path.Combine(root,"test.txt")),"new\n");passed++;
    Equal(AppPatchBuilder.IsRepositoryMetadata("windows/CubicalCompare.PatchTests/CubicalCompare.PatchTests.csproj"),true);
    Equal(AppPatchBuilder.IsRepositoryMetadata("windows/CubicalCompare.UpdateSmoke/Program.cs"),true);
    Equal(AppPatchBuilder.IsRepositoryMetadata("windows/CubicalCompare.Modules/Updates/UpdateService.cs"),false);passed++;
    File.WriteAllText(first,Diff("/dev/null","windows/CubicalCompare.PatchTests/CubicalCompare.PatchTests.csproj","@@ -0,0 +1 @@\n+older-test-project\n"));
    using(var session=await AppPatchBuilder.PrepareAsync(zip,[first],Path.Combine(root,"jobs"))) {
        Equal(session.ChangedFiles.Count,0);Equal(session.SkippedFiles.Count,1);passed++;
    }
    File.WriteAllText(first,Diff("test.txt","test.txt","@@ -1 +1 @@\n-zero\n+one\n"));
    using(var session=await AppPatchBuilder.PrepareAsync(zip,[first],Path.Combine(root,"jobs"))) {
        Equal(session.ChangedFiles.Count,0);Equal(session.AlreadyAppliedFiles.Count,1);passed++;
    }
    Rejected(Diff("test.txt","test.txt","@@ -1 +0,0 @@\n-other\n"));
    Console.WriteLine("PASS repeat imports: identical creation, CRLF/BOM, exact reverse checks, conflicts, mixed patches and test-project filtering.");
    var ready=new FakeSetupHost(true,true,true);
    var environment=await BuildToolBootstrap.EnsureAsync(host:ready);
    Equal(ready.Installed.Count,0);Equal(environment.Dotnet,"dotnet-ready.exe");passed++;
    var missing=new FakeSetupHost(false,false,false);
    environment=await BuildToolBootstrap.EnsureAsync(host:missing);
    Equal(string.Join(",",missing.Installed),"Microsoft.DotNet.SDK.10,Microsoft.PowerShell,Microsoft.WindowsSDK.10.0.26100");
    Equal(environment.PowerShell,"pwsh-ready.exe");passed++;
    foreach(var state in new[]{(false,true,true),(true,false,true),(true,true,false)}) {
        var host=new FakeSetupHost(state.Item1,state.Item2,state.Item3);
        await BuildToolBootstrap.EnsureAsync(host:host);Equal(host.Installed.Count,1);passed++;
    }
    var noManager=new FakeSetupHost(false,true,true){PackageManager=null};
    try {await BuildToolBootstrap.EnsureAsync(host:noManager);throw new Exception("Missing package manager accepted");}
    catch(InvalidOperationException){Equal(noManager.Installed.Count,0);passed++;}
    var failed=new FakeSetupHost(false,false,false){FailInstall=true};
    try {await BuildToolBootstrap.EnsureAsync(host:failed);throw new Exception("Failed install accepted");}
    catch(InvalidOperationException){Equal(failed.Installed.Count,1);passed++;}
    var noDetection=new FakeSetupHost(false,true,true){InstallChangesDetection=false};
    try {await BuildToolBootstrap.EnsureAsync(host:noDetection);throw new Exception("Missing tool after installer accepted");}
    catch(InvalidOperationException){Equal(noDetection.Installed.Count,1);passed++;}
    var cancelled=new FakeSetupHost(false,false,false){CancelInstall=true};
    try {await BuildToolBootstrap.EnsureAsync(host:cancelled);throw new Exception("Cancelled installation continued");}
    catch(OperationCanceledException){Equal(cancelled.Installed.Count,1);passed++;}
    foreach(var id in new[]{"Microsoft.DotNet.SDK.10","Microsoft.PowerShell","Microsoft.WindowsSDK.10.0.26100"}) {
        var arguments=BuildToolBootstrap.InstallArguments(id);
        Equal(arguments.Contains("--interactive"),true);Equal(arguments.Contains("--silent"),false);
        Equal(arguments.Contains("--source"),true);Equal(arguments.Contains(id),true);passed++;
    }
    Console.WriteLine("PASS 12 build-tool bootstrap checks: reuse, selective installs, detection after setup, failure/cancellation and visible installer flags.");
    Console.WriteLine($"PASS {passed} patch checks: exact context, create/delete/rename, multi-hunk, EOF/CRLF, paths/links, atomic preparation, sequential imports, cancellation and source isolation.");
}
finally { Directory.Delete(root,true); }

sealed class FakeSetupHost(bool dotnet, bool powershell, bool windowsSdk) : IBuildToolSetupHost
{
    public List<string> Installed { get; } = [];
    public string? PackageManager { get; init; }="winget-test.exe";
    public bool FailInstall { get; init; }
    public bool CancelInstall { get; init; }
    public bool InstallChangesDetection { get; init; }=true;
    public Task<string?> FindDotnetSdkAsync(CancellationToken token) {token.ThrowIfCancellationRequested();return Task.FromResult(dotnet ? "dotnet-ready.exe" : null);}
    public Task<string?> FindPowerShellAsync(CancellationToken token) {token.ThrowIfCancellationRequested();return Task.FromResult(powershell ? "pwsh-ready.exe" : null);}
    public bool HasWindowsSdk()=>windowsSdk;
    public string? FindPackageManager()=>PackageManager;
    public Task InstallAsync(string manager,string id,string name,IProgress<string>? progress,CancellationToken token)
    {
        token.ThrowIfCancellationRequested();Installed.Add(id);
        if(CancelInstall)throw new OperationCanceledException();
        if(FailInstall)throw new InvalidOperationException("Installer failed");
        if(InstallChangesDetection){if(id=="Microsoft.DotNet.SDK.10")dotnet=true;if(id=="Microsoft.PowerShell")powershell=true;if(id=="Microsoft.WindowsSDK.10.0.26100")windowsSdk=true;}
        return Task.CompletedTask;
    }
}
