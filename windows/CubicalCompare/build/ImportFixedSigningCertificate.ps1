param()

$ErrorActionPreference = 'Stop'
$expectedSubject = 'CN=RetroFrost Development'
$expectedThumbprint = '52C53117BB543E6D5FE401F850CA0A9197948263'

if ([string]::IsNullOrWhiteSpace($env:WINDOWS_SIGNING_PFX_BASE64)) {
    throw 'Repository secret WINDOWS_SIGNING_PFX_BASE64 is missing. Rotating CI certificates are intentionally disabled.'
}
if ([string]::IsNullOrWhiteSpace($env:WINDOWS_SIGNING_PFX_PASSWORD)) {
    throw 'Repository secret WINDOWS_SIGNING_PFX_PASSWORD is missing.'
}
if ([string]::IsNullOrWhiteSpace($env:GITHUB_ENV)) {
    throw 'GITHUB_ENV is not available; this script is intended to run inside GitHub Actions.'
}

$pfx = Join-Path $env:RUNNER_TEMP 'CubicalCompare-Fixed-CodeSigning.pfx'
$cer = Join-Path $env:RUNNER_TEMP 'CubicalCompare-Fixed-CodeSigning.cer'

try {
    [IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($env:WINDOWS_SIGNING_PFX_BASE64))
    $password = ConvertTo-SecureString $env:WINDOWS_SIGNING_PFX_PASSWORD -AsPlainText -Force
    $imported = @(Import-PfxCertificate -FilePath $pfx -CertStoreLocation 'Cert:\CurrentUser\My' -Password $password -Exportable)
    $cert = $imported | Where-Object {
        $_.HasPrivateKey -and
        $_.Subject -eq $expectedSubject -and
        $_.Thumbprint -eq $expectedThumbprint
    } | Select-Object -First 1

    if (-not $cert) {
        $seen = ($imported | ForEach-Object { "$($_.Subject) [$($_.Thumbprint)] private=$($_.HasPrivateKey)" }) -join '; '
        throw "The configured PFX is not the pinned Cubical Compare signing identity $expectedThumbprint. Imported: $seen"
    }
    if ((Get-Date) -lt $cert.NotBefore -or (Get-Date) -gt $cert.NotAfter) {
        throw "The fixed signing certificate is outside its validity window ($($cert.NotBefore) - $($cert.NotAfter))."
    }

    Export-Certificate -Cert $cert -FilePath $cer -Force | Out-Null
    Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\CurrentUser\TrustedPeople' | Out-Null
    Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null

    "CC_CERT_THUMBPRINT=$($cert.Thumbprint)" | Out-File -FilePath $env:GITHUB_ENV -Append
    "CC_CER=$cer" | Out-File -FilePath $env:GITHUB_ENV -Append
    Write-Host "Loaded fixed signing certificate $($cert.Thumbprint), valid through $($cert.NotAfter.ToUniversalTime().ToString('u'))."
}
finally {
    if (Test-Path $pfx) { Remove-Item $pfx -Force }
}
