$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
$out = Join-Path (Split-Path $PSScriptRoot -Parent) "CubicalCompareV5\Assets\CubicalCompare.ico"
New-Item -ItemType Directory -Path (Split-Path $out) -Force | Out-Null
$b = [System.Drawing.Bitmap]::new(256,256)
$g = [System.Drawing.Graphics]::FromImage($b)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([System.Drawing.Color]::Transparent)
$bg = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#183044"))
$g.FillEllipse($bg, 5,5,246,246)
function Face($pts, $hex) {
  $brush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml($hex))
  $g.FillPolygon($brush, [System.Drawing.Point[]]$pts)
  $brush.Dispose()
}
Face @([System.Drawing.Point]::new(128,31),[System.Drawing.Point]::new(228,85),[System.Drawing.Point]::new(128,141),[System.Drawing.Point]::new(28,85)) "#6CE6E6"
Face @([System.Drawing.Point]::new(28,85),[System.Drawing.Point]::new(128,141),[System.Drawing.Point]::new(128,233),[System.Drawing.Point]::new(28,179)) "#2587CE"
Face @([System.Drawing.Point]::new(228,85),[System.Drawing.Point]::new(128,141),[System.Drawing.Point]::new(128,233),[System.Drawing.Point]::new(228,179)) "#33C7BF"
$g.Dispose()
$ms = [System.IO.MemoryStream]::new()
$b.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
$b.Dispose()
$data = $ms.ToArray()
$ms.Dispose()
$w = [System.IO.BinaryWriter]::new([System.IO.File]::Open($out,[System.IO.FileMode]::Create))
$w.Write([uint16]0);$w.Write([uint16]1);$w.Write([uint16]1)
$w.Write([byte]0);$w.Write([byte]0);$w.Write([byte]0);$w.Write([byte]0)
$w.Write([uint16]1);$w.Write([uint16]32);$w.Write([uint32]$data.Length);$w.Write([uint32]22)
$w.Write($data);$w.Dispose()
Write-Host "Icon ready: $out"