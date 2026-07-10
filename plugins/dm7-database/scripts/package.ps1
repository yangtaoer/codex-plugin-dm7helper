$ErrorActionPreference = 'Stop'

$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $pluginRoot '..\..')).Path
$dist = Join-Path $repoRoot 'dist'
$stageRoot = Join-Path $dist '.package-stage'
$stagePlugin = Join-Path $stageRoot 'dm7-database'
$archive = Join-Path $dist 'dm7-database-0.1.0.zip'

function Test-ByteSequence([byte[]]$Bytes, [byte[]]$Pattern) {
  if ($Pattern.Length -eq 0 -or $Bytes.Length -lt $Pattern.Length) { return $false }
  for ($offset = 0; $offset -le $Bytes.Length - $Pattern.Length; $offset++) {
    $matches = $true
    for ($index = 0; $index -lt $Pattern.Length; $index++) {
      if ($Bytes[$offset + $index] -ne $Pattern[$index]) {
        $matches = $false
        break
      }
    }
    if ($matches) { return $true }
  }
  return $false
}

function Assert-NoIntegrationValues([string]$Root) {
  $values = @(
    $env:DM7_IT_JDBC_URL,
    $env:DM7_IT_USERNAME,
    $env:DM7_IT_PASSWORD,
    $env:DM7_IT_DRIVER_JAR
  ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

  foreach ($value in $values) {
    $patterns = @(
      [Text.Encoding]::UTF8.GetBytes($value),
      [Text.Encoding]::Unicode.GetBytes($value),
      [Text.Encoding]::BigEndianUnicode.GetBytes($value)
    )
    foreach ($file in Get-ChildItem -LiteralPath $Root -Recurse -File) {
      $bytes = [IO.File]::ReadAllBytes($file.FullName)
      foreach ($pattern in $patterns) {
        if (Test-ByteSequence $bytes $pattern) {
          throw 'Package contains an integration environment value'
        }
      }
    }
  }
}

Push-Location $repoRoot
try {
  & (Join-Path $PSScriptRoot 'test.ps1')
  & (Join-Path $PSScriptRoot 'build.ps1')

  New-Item -ItemType Directory -Force -Path $dist | Out-Null
  if (Test-Path -LiteralPath $stageRoot) { Remove-Item -LiteralPath $stageRoot -Recurse -Force }
  if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
  New-Item -ItemType Directory -Force -Path $stagePlugin | Out-Null

  $runtimeWhitelist = @(
    '.codex-plugin',
    '.mcp.json',
    'assets',
    'hooks',
    'lib',
    'skills',
    'README.md',
    'LICENSE',
    'THIRD_PARTY_NOTICES.md'
  )
  foreach ($relative in $runtimeWhitelist) {
    $source = Join-Path $pluginRoot $relative
    if (Test-Path -LiteralPath $source) {
      Copy-Item -LiteralPath $source -Destination (Join-Path $stagePlugin $relative) -Recurse -Force
    }
  }

  foreach ($required in @('.codex-plugin\plugin.json', '.mcp.json', 'lib\dm7-codex-plugin.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $stagePlugin $required) -PathType Leaf)) {
      throw "Required runtime file is missing: $required"
    }
  }

  $forbiddenFiles = Get-ChildItem -LiteralPath $stagePlugin -Recurse -File | Where-Object {
    $_.Name -like 'Dm*Jdbc*.jar' -or
    $_.Name -like '.env*' -or
    $_.Name -in @('vault.json', 'master.key')
  }
  if ($forbiddenFiles) { throw 'Package contains forbidden files' }
  Assert-NoIntegrationValues $stagePlugin

  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [IO.Compression.ZipFile]::Open($archive, [IO.Compression.ZipArchiveMode]::Create)
  try {
    $timestamp = [DateTimeOffset]::FromUnixTimeSeconds(315532800)
    if ($env:SOURCE_DATE_EPOCH -match '^\d+$') {
      $timestamp = [DateTimeOffset]::FromUnixTimeSeconds([long]$env:SOURCE_DATE_EPOCH)
    }
    foreach ($file in Get-ChildItem -LiteralPath $stagePlugin -Recurse -File | Sort-Object FullName) {
      $relative = $file.FullName.Substring($stagePlugin.Length).TrimStart('\', '/') -replace '\\', '/'
      $entry = $zip.CreateEntry("dm7-database/$relative", [IO.Compression.CompressionLevel]::Optimal)
      $entry.LastWriteTime = $timestamp
      $input = [IO.File]::OpenRead($file.FullName)
      $output = $entry.Open()
      try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
    }
  } finally {
    $zip.Dispose()
  }
  Write-Output $archive
} finally {
  if (Test-Path -LiteralPath $stageRoot) { Remove-Item -LiteralPath $stageRoot -Recurse -Force }
  Pop-Location
}
