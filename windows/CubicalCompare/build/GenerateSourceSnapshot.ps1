param([Parameter(Mandatory=$true)][string]$RepositoryRoot, [Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = [IO.Path]::GetFullPath($RepositoryRoot)
$output = [IO.Path]::GetFullPath($OutputPath)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($output)) | Out-Null
$temp = "$output.new"
try {
    $stream = [IO.File]::Open($temp, [IO.FileMode]::Create)
    try {
        $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
        try {
            $paths = @([IO.Directory]::EnumerateFiles($root))
            foreach ($folder in @('windows', '.github')) {
                $directory = Join-Path $root $folder
                if (Test-Path $directory) { $paths += [IO.Directory]::EnumerateFiles($directory, '*', [IO.SearchOption]::AllDirectories) }
            }
            foreach ($file in $paths) {
                $relative = [IO.Path]::GetRelativePath($root, $file).Replace('\', '/')
                if ($relative -match '(^|/)(bin|obj|\.git|artifacts|\.vs)(/|$)' -or $file -eq $output -or $file -eq $temp) { continue }
                if (([IO.File]::GetAttributes($file) -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Source links are not supported: $file" }
                if ($relative -match '\.(user|suo|pfx|snk)$') { continue }
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file, $relative, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
            }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
    Move-Item $temp $output -Force
} finally { if (Test-Path $temp) { Remove-Item $temp -Force } }
