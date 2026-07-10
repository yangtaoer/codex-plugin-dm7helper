$ErrorActionPreference = 'Stop'

$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $pluginRoot '..\..')).Path

& (Join-Path $PSScriptRoot 'test.ps1')
& (Join-Path $PSScriptRoot 'build.ps1')

$dist = Join-Path $repoRoot 'dist'
$archive = Join-Path $dist 'dm7-database-0.1.0.zip'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
if (Test-Path -LiteralPath $archive) {
  Remove-Item -LiteralPath $archive
}
Compress-Archive -Path (Join-Path $pluginRoot '*') -DestinationPath $archive -CompressionLevel Optimal
Write-Output $archive
