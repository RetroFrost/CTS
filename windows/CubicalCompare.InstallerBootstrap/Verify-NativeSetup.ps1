param(
    [Parameter(Mandatory=$true)][string]$Setup
)
$ErrorActionPreference = 'Stop'
$Setup = (Resolve-Path $Setup).Path
$file = Get-Item $Setup
$stream = [IO.File]::OpenRead($Setup)
try {
    if ($stream.Length -lt 16) { throw 'Setup is too small to contain its payload footer.' }
    [void]$stream.Seek(-16, [IO.SeekOrigin]::End)
    $tail = New-Object byte[] 16
    if ($stream.Read($tail, 0, 16) -ne 16) { throw 'Could not read setup payload footer.' }
    $magic = [Text.Encoding]::ASCII.GetString($tail, 0, 8)
    $payloadSize = [BitConverter]::ToInt64($tail, 8)
    $launcherSize = $stream.Length - 16 - $payloadSize
}
finally {
    $stream.Dispose()
}
if ($magic -ne 'CCVPK001') { throw "Unexpected setup footer magic: $magic" }
if ($payloadSize -le 0 -or $payloadSize -ge $file.Length) { throw 'Setup payload size is invalid.' }
if ($launcherSize -le 0 -or $launcherSize -gt 3MB) {
    throw "Native bootstrap is unexpectedly large: $([math]::Round($launcherSize / 1KB, 1)) KiB."
}
Write-Host "Native bootstrap verified: $([math]::Round($launcherSize / 1KB, 1)) KiB launcher + $([math]::Round($payloadSize / 1MB, 2)) MiB payload."
