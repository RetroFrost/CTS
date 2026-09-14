param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

function New-CubicalCompareAsset {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$Width,
        [Parameter(Mandatory = $true)][int]$Height
    )

    $path = Join-Path $OutputDirectory $Name
    $bitmap = [System.Drawing.Bitmap]::new($Width, $Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.Clear([System.Drawing.Color]::FromArgb(255, 20, 20, 20))

        $margin = [Math]::Max(2, [Math]::Floor([Math]::Min($Width, $Height) * 0.12))
        $badgeWidth = [Math]::Max(1, $Width - ($margin * 2))
        $badgeHeight = [Math]::Max(1, $Height - ($margin * 2))
        $radius = [Math]::Max(2, [Math]::Floor([Math]::Min($badgeWidth, $badgeHeight) * 0.18))

        $pathShape = [System.Drawing.Drawing2D.GraphicsPath]::new()
        try {
            $diameter = $radius * 2
            $x = $margin
            $y = $margin
            $right = $x + $badgeWidth
            $bottom = $y + $badgeHeight
            $pathShape.AddArc($x, $y, $diameter, $diameter, 180, 90)
            $pathShape.AddArc($right - $diameter, $y, $diameter, $diameter, 270, 90)
            $pathShape.AddArc($right - $diameter, $bottom - $diameter, $diameter, $diameter, 0, 90)
            $pathShape.AddArc($x, $bottom - $diameter, $diameter, $diameter, 90, 90)
            $pathShape.CloseFigure()

            $yellow = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 255, 255, 0))
            try { $graphics.FillPath($yellow, $pathShape) } finally { $yellow.Dispose() }
        }
        finally {
            $pathShape.Dispose()
        }

        # A compact geometric CC mark: two dark open rings, no font dependency.
        $penWidth = [Math]::Max(2, [Math]::Floor([Math]::Min($Width, $Height) * 0.075))
        $pen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(255, 20, 20, 20), $penWidth)
        try {
            $ringSize = [Math]::Max(4, [Math]::Floor([Math]::Min($Width, $Height) * 0.36))
            $gap = [Math]::Max(1, [Math]::Floor($ringSize * 0.08))
            $totalWidth = ($ringSize * 2) + $gap
            $startX = [Math]::Floor(($Width - $totalWidth) / 2)
            $startY = [Math]::Floor(($Height - $ringSize) / 2)
            $graphics.DrawArc($pen, $startX, $startY, $ringSize, $ringSize, 45, 270)
            $graphics.DrawArc($pen, $startX + $ringSize + $gap, $startY, $ringSize, $ringSize, 45, 270)
        }
        finally {
            $pen.Dispose()
        }

        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Expand-BundledFont {
    param(
        [Parameter(Mandatory = $true)][string]$ArchiveName,
        [Parameter(Mandatory = $true)][string]$OutputName
    )

    $archivePath = Join-Path $PSScriptRoot $ArchiveName
    if (-not (Test-Path -LiteralPath $archivePath)) {
        throw "Bundled font archive is missing: $archivePath"
    }

    $fontPath = Join-Path $OutputDirectory $OutputName
    $input = [System.IO.File]::OpenRead($archivePath)
    try {
        $gzip = [System.IO.Compression.GZipStream]::new($input, [System.IO.Compression.CompressionMode]::Decompress)
        try {
            $output = [System.IO.File]::Create($fontPath)
            try {
                $gzip.CopyTo($output)
            }
            finally {
                $output.Dispose()
            }
        }
        finally {
            $gzip.Dispose()
        }
    }
    finally {
        $input.Dispose()
    }

    if ((Get-Item -LiteralPath $fontPath).Length -lt 1024) {
        throw "Generated bundled font looks invalid: $fontPath"
    }
}

New-CubicalCompareAsset -Name 'StoreLogo.png' -Width 50 -Height 50
New-CubicalCompareAsset -Name 'Square44x44Logo.png' -Width 44 -Height 44
New-CubicalCompareAsset -Name 'Square150x150Logo.png' -Width 150 -Height 150
New-CubicalCompareAsset -Name 'Wide310x150Logo.png' -Width 310 -Height 150
New-CubicalCompareAsset -Name 'SplashScreen.png' -Width 620 -Height 300
Expand-BundledFont -ArchiveName 'nexa-extrabold.ttf.gz' -OutputName 'nexa-extrabold.ttf'

Write-Host "Generated package assets and Nexa ExtraBold in $OutputDirectory"
