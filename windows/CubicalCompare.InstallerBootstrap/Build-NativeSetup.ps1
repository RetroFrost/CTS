param(
    [Parameter(Mandatory=$true)][string]$VelopackSetup,
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$true)][string]$PublishDirectory,
    [Parameter(Mandatory=$true)][string]$AppVersion
)
$ErrorActionPreference = 'Stop'

$VelopackSetup = (Resolve-Path $VelopackSetup).Path
$source = (Resolve-Path (Join-Path $PSScriptRoot 'NativeSetup.cpp')).Path
$outDir = Split-Path -Parent $Output
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$tempDir = Join-Path $outDir 'native-bootstrap'
Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $vswhere)) { throw 'vswhere.exe was not found.' }
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if ([string]::IsNullOrWhiteSpace($vs)) { throw 'Visual C++ build tools were not found.' }
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
if (-not (Test-Path $vcvars)) { throw 'vcvars64.bat was not found.' }

$PublishDirectory = (Resolve-Path $PublishDirectory).Path
if ($AppVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid visible app version.' }
$checks = @('CubicalCompare.dll', 'CubicalCompare.Core.dll', 'CubicalCompare.Renderer.dll', 'CubicalCompare.MegaPack.dll', 'CubicalCompare.Thumbnail.dll', 'CubicalCompare.Updates.dll', 'resources.pri', 'Assets/AppSource.zip')
$header = @('struct PayloadFile { const wchar_t* path; const char* sha256; };', "constexpr wchar_t kAppVersion[] = L`"$AppVersion`";", 'constexpr PayloadFile kPayloadFiles[] = {')
foreach ($relative in $checks) {
    $file = Join-Path $PublishDirectory $relative
    if (-not (Test-Path $file)) { throw "Missing setup verification payload: $relative" }
    $hash = (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()
    $header += "    { L`"$relative`", `"$hash`" },"
}
$header += '};'
$header | Set-Content (Join-Path $tempDir 'SetupPayload.h') -Encoding ASCII
$manifest = (Resolve-Path (Join-Path $PSScriptRoot 'setup.manifest')).Path
$launcher = Join-Path $tempDir 'CubicalCompare.Setup.exe'
$cmdFile = Join-Path $tempDir 'build.cmd'
@"
@echo off
call "$vcvars"
if errorlevel 1 exit /b %errorlevel%
cl.exe /nologo /std:c++17 /O2 /MT /EHsc /I"$tempDir" "$source" /link /MANIFEST:EMBED /MANIFESTINPUT:"$manifest" /SUBSYSTEM:WINDOWS /OUT:"$launcher" user32.lib gdi32.lib comctl32.lib shell32.lib ole32.lib bcrypt.lib advapi32.lib dwmapi.lib
exit /b %errorlevel%
"@ | Set-Content $cmdFile -Encoding ASCII

& cmd.exe /d /c $cmdFile
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $launcher)) {
    throw "Native setup bootstrap compilation failed with exit code $LASTEXITCODE."
}

Copy-Item $launcher $Output -Force
$destination = [IO.File]::Open($Output, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)
try {
    $payload = [IO.File]::OpenRead($VelopackSetup)
    try { $payload.CopyTo($destination) }
    finally { $payload.Dispose() }

    $magic = [Text.Encoding]::ASCII.GetBytes('CCVPK001')
    $destination.Write($magic, 0, $magic.Length)
    $lengthBytes = [BitConverter]::GetBytes([int64](Get-Item $VelopackSetup).Length)
    $destination.Write($lengthBytes, 0, $lengthBytes.Length)
}
finally {
    $destination.Dispose()
}

Write-Host "Built native Cubical Compare setup: $Output"
