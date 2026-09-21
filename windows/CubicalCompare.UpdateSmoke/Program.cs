using CubicalCompare.Updates;

if (args.Length != 2)
{
    Console.Error.WriteLine("Usage: CubicalCompare.UpdateSmoke <portable.zip> <simulated-running-directory>");
    return 2;
}

var zip = Path.GetFullPath(args[0]);
var simulatedRunningDirectory = Path.GetFullPath(args[1]);

var service = new CubicalUpdateService();
var progress = new Progress<CubicalUpdateProgress>(state =>
    Console.WriteLine($"{state.Percent,3}% {state.Phase}"));

var started = await service.ApplyPortableZipFileAsync(
    zip,
    simulatedRunningDirectory,
    "CubicalCompare.exe",
    progress,
    CancellationToken.None,
    restartAfterUpdate: false);

if (!started)
{
    Console.Error.WriteLine("Portable update helper was not started.");
    return 3;
}

Console.WriteLine("Portable update helper started; exiting so it can replace the simulated running tree.");
return 0;
