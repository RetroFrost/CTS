param(
    [Parameter(Mandatory = $true)][string]$PackagePath,
    [Parameter(Mandatory = $true)][string]$CertificatePath,
    [string]$OutputDirectory = 'artifacts/CubicalCompare-4-MSIX'
)

$ErrorActionPreference = 'Stop'
$expectedThumbprint = '548F320EFE4885F54B93F254606BB723DB37FF99'

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$msix = Join-Path $OutputDirectory 'CubicalCompare-4-x64.msix'
$cer = Join-Path $OutputDirectory 'CubicalCompare-Development.cer'
Copy-Item $PackagePath $msix -Force
Copy-Item $CertificatePath $cer -Force

$publicCert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($cer)
if ($publicCert.Subject -ne 'CN=RetroFrost Development' -or $publicCert.Thumbprint -ne $expectedThumbprint) {
    throw "Staged certificate is not the pinned Cubical Compare signer: $($publicCert.Subject) [$($publicCert.Thumbprint)]"
}

@'
param()
$ErrorActionPreference = 'Stop'
$expectedThumbprint = '548F320EFE4885F54B93F254606BB723DB37FF99'

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '"'
    $child = Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments -Wait -PassThru
    exit $child.ExitCode
}

$root = Split-Path -Parent $PSCommandPath
$cer = Join-Path $root 'CubicalCompare-Development.cer'
$msix = Join-Path $root 'CubicalCompare-4-x64.msix'
$log = Join-Path $env:TEMP ('CubicalCompare-install-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$exitCode = 0
Start-Transcript -Path $log -Force | Out-Null

try {
    if (-not (Test-Path $cer)) { throw 'CubicalCompare-Development.cer is missing.' }
    if (-not (Test-Path $msix)) { throw 'CubicalCompare-4-x64.msix is missing.' }

    $build = [int](Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').CurrentBuildNumber
    if ($build -lt 19041) { throw "Cubical Compare 4 requires Windows 10 build 19041 or newer. This PC is build $build." }

    $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($cer)
    if ($cert.Subject -ne 'CN=RetroFrost Development') { throw "Unexpected certificate subject: $($cert.Subject)" }
    if ($cert.Thumbprint -ne $expectedThumbprint) { throw "Unexpected certificate thumbprint: $($cert.Thumbprint)" }
    if ((Get-Date) -lt $cert.NotBefore -or (Get-Date) -gt $cert.NotAfter) { throw 'The Cubical Compare signing certificate is not currently valid.' }

    $trusted = Get-ChildItem 'Cert:\LocalMachine\TrustedPeople' -ErrorAction SilentlyContinue |
        Where-Object Thumbprint -eq $expectedThumbprint |
        Select-Object -First 1
    if (-not $trusted) {
        Write-Host 'Trusting the fixed Cubical Compare signing certificate (one-time setup)...'
        Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
    }

    $signature = Get-AuthenticodeSignature -FilePath $msix
    if (-not $signature.SignerCertificate -or $signature.SignerCertificate.Thumbprint -ne $expectedThumbprint) {
        throw 'The MSIX signer does not match the pinned Cubical Compare certificate.'
    }
    if ($signature.Status -ne 'Valid') { throw "MSIX signature verification failed: $($signature.Status) $($signature.StatusMessage)" }

    Add-AppxPackage -Path $msix -ForceApplicationShutdown -ErrorAction Stop
    $installed = Get-AppxPackage -Name 'RetroFrost.CubicalCompare' | Sort-Object Version -Descending | Select-Object -First 1
    if (-not $installed) { throw 'Windows did not register Cubical Compare after installation.' }
    Write-Host "Installed Cubical Compare $($installed.Version)."
}
catch {
    $exitCode = 1
    Write-Host ''
    Write-Host 'Cubical Compare installation failed:'
    Write-Host $_.Exception.Message
    Write-Host "Diagnostic log: $log"
}
finally {
    try { Stop-Transcript | Out-Null } catch {}
}

exit $exitCode
'@ | Set-Content (Join-Path $OutputDirectory 'Install-CubicalCompare.ps1') -Encoding UTF8

@'
@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-CubicalCompare.ps1"
if errorlevel 1 (
  echo.
  echo Cubical Compare installation failed. See the message above.
  pause
  exit /b 1
)
echo.
echo Cubical Compare installation completed.
pause
'@ | Set-Content (Join-Path $OutputDirectory 'Install-CubicalCompare.cmd') -Encoding ASCII

@'
Cubical Compare 4 - verified Windows release package

QUICK INSTALL
1. Extract this entire ZIP to a normal folder.
2. Double-click Install-CubicalCompare.cmd.
3. Accept the administrator prompt if Windows needs to trust the signer for the first time.
4. The installer pins the Cubical Compare certificate thumbprint, verifies the MSIX signature, trusts that exact certificate, and installs through the Windows deployment API.

FIXED SIGNING CERTIFICATE
Cubical Compare Windows releases use one effectively-permanent signing identity instead of generating a new certificate for each build. Its X.509 NotAfter is 31 December 9999. After this certificate is trusted once, later releases signed by the same identity reuse that trust.

The signing private key is never included in release artifacts. Releases contain only the public certificate required for signature verification and first-time trust.
'@ | Set-Content (Join-Path $OutputDirectory 'README.txt') -Encoding UTF8
