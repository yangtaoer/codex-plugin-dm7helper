$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $pluginRoot '..\..')).Path
$dist = Join-Path $repoRoot 'dist'
$stageRoot = Join-Path $dist '.package-stage'
$stagePlugin = Join-Path $stageRoot 'dm7-database'
$archive = Join-Path $dist 'dm7-database-0.1.0.zip'
$runtimeJar = Join-Path $pluginRoot 'lib\dm7-codex-plugin.jar'

function Test-ByteSequence([byte[]]$Bytes, [byte[]]$Pattern) {
  if ($Pattern.Length -eq 0 -or $Bytes.Length -lt $Pattern.Length) { return $false }
  for ($offset = 0; $offset -le $Bytes.Length - $Pattern.Length; $offset++) {
    $matches = $true
    for ($index = 0; $index -lt $Pattern.Length; $index++) {
      if ($Bytes[$offset + $index] -ne $Pattern[$index]) { $matches = $false; break }
    }
    if ($matches) { return $true }
  }
  return $false
}

function Assert-NoIntegrationValues([string]$Root) {
  $values = @($env:DM7_IT_JDBC_URL, $env:DM7_IT_USERNAME, $env:DM7_IT_PASSWORD, $env:DM7_IT_DRIVER_JAR) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
  $files = @(Get-ChildItem -LiteralPath $Root -Recurse -File)
  foreach ($value in $values) {
    $patterns = @([Text.Encoding]::UTF8.GetBytes($value), [Text.Encoding]::Unicode.GetBytes($value), [Text.Encoding]::BigEndianUnicode.GetBytes($value))
    foreach ($file in $files) {
      $bytes = [IO.File]::ReadAllBytes($file.FullName)
      foreach ($pattern in $patterns) {
        if (Test-ByteSequence $bytes $pattern) { throw 'Package contains an integration environment value' }
      }
    }
  }
}

function Get-Sha256([string]$Path) {
  $algorithm = [Security.Cryptography.SHA256]::Create()
  $stream = [IO.File]::OpenRead($Path)
  try { return -join ($algorithm.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) }
  finally { $stream.Dispose(); $algorithm.Dispose() }
}

function Assert-NoForbiddenRuntimeFiles {
  $inspectionRoots = @('assets', 'hooks', 'lib', 'skills', 'docs', 'licenses') | ForEach-Object { Join-Path $pluginRoot $_ } |
    Where-Object { Test-Path -LiteralPath $_ }
  $files = @($inspectionRoots | ForEach-Object { Get-ChildItem -LiteralPath $_ -Recurse -File })
  $topFiles = @(Get-ChildItem -LiteralPath $pluginRoot -File)
  $forbidden = @($files + $topFiles | Where-Object {
    $_.Name -like 'Dm*Jdbc*.jar' -or
    ($_.Extension -eq '.jar' -and $_.FullName -ne $runtimeJar) -or
    $_.Name -like '*.env*' -or
    $_.Name -in @('vault.json', 'master.key') -or
    $_.Extension -in @('.zip', '.7z', '.tar', '.tgz', '.gz', '.bz2', '.xz', '.map') -or
    $_.FullName -match '[\\/](node_modules|target|test-results|\.cache|release|state)[\\/]'
  })
  if ($forbidden.Count -gt 0) { throw 'Package contains forbidden files' }
}

function Copy-RuntimeFile([string]$Relative, [bool]$Required = $true) {
  $source = Join-Path $pluginRoot $Relative
  if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    if ($Required) { throw "Required runtime file is missing: $Relative" }
    return
  }
  $destination = Join-Path $stagePlugin $Relative
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
  Copy-Item -LiteralPath $source -Destination $destination -Force
}

Push-Location $repoRoot
try {
  if ([string]::IsNullOrWhiteSpace($env:SOURCE_DATE_EPOCH)) {
    if (Test-Path -LiteralPath (Join-Path $repoRoot '.git')) {
      $derivedEpoch = git -C $repoRoot log -1 --format=%ct
      $env:SOURCE_DATE_EPOCH = if ($LASTEXITCODE -eq 0 -and $derivedEpoch) { $derivedEpoch.Trim() } else { '315532800' }
    } else { $env:SOURCE_DATE_EPOCH = '315532800' }
  }
  if ($env:SOURCE_DATE_EPOCH -notmatch '^\d+$') { throw 'SOURCE_DATE_EPOCH must be a Unix timestamp' }
  & (Join-Path $PSScriptRoot 'verify-extracted.ps1') -CheckJava17Only
  $licenseRelativeFiles = @(python (Join-Path $PSScriptRoot 'verify-license-inventory.py') $pluginRoot --list)
  if ($LASTEXITCODE -ne 0) { throw 'Dependency license inventory validation failed' }

  Assert-NoForbiddenRuntimeFiles
  foreach ($scanRoot in @('assets', 'hooks', 'lib', 'skills', 'docs', 'licenses')) {
    $scanPath = Join-Path $pluginRoot $scanRoot
    if (Test-Path -LiteralPath $scanPath) { Assert-NoIntegrationValues $scanPath }
  }

  & (Join-Path $PSScriptRoot 'test.ps1')

  & (Join-Path $PSScriptRoot 'build.ps1')
  if (-not (Test-Path -LiteralPath $runtimeJar -PathType Leaf)) { throw 'Runtime JAR was not produced' }
  $FirstJarHash = Get-Sha256 $runtimeJar
  & (Join-Path $PSScriptRoot 'build.ps1')
  if (-not (Test-Path -LiteralPath $runtimeJar -PathType Leaf)) { throw 'Runtime JAR was not reproduced' }
  $SecondJarHash = Get-Sha256 $runtimeJar
  if ($FirstJarHash -ne $SecondJarHash) { throw 'Runtime JAR reproducibility check failed' }

  New-Item -ItemType Directory -Force -Path $dist | Out-Null
  if (Test-Path -LiteralPath $stageRoot) { Remove-Item -LiteralPath $stageRoot -Recurse -Force }
  if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
  New-Item -ItemType Directory -Force -Path $stagePlugin | Out-Null

  $requiredRuntimeFiles = @(
    '.codex-plugin\plugin.json', '.mcp.json',
    'assets\icon.svg',
    'hooks\hooks.json', 'hooks\session-context.ps1',
    'lib\dm7-codex-plugin.jar', 'skills\dm7-database\SKILL.md',
    'README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md'
  )
  $optionalRuntimeFiles = @(
    'assets\logo.svg', 'assets\logo-dark.svg', 'assets\screenshot-console.png', 'assets\screenshot-release.png',
    'SECURITY.md', 'CHANGELOG.md',
    'docs\INSTALLATION.md', 'docs\USER_GUIDE.md', 'docs\TROUBLESHOOTING.md',
    'docs\DEVELOPMENT.md', 'docs\PACKAGING.md', 'docs\ADMINISTRATION.md', 'docs\LICENSING.md'
  )
  foreach ($relative in $requiredRuntimeFiles) { Copy-RuntimeFile $relative $true }
  foreach ($relative in $optionalRuntimeFiles) { Copy-RuntimeFile $relative $false }
  foreach ($relative in $licenseRelativeFiles) {
    Copy-RuntimeFile $relative $true
  }
  Assert-NoIntegrationValues $stagePlugin
  python (Join-Path $PSScriptRoot 'verify-package-security.py') $stagePlugin
  if ($LASTEXITCODE -ne 0) { throw 'Recursive package security scan failed' }

  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $epoch = [long]$env:SOURCE_DATE_EPOCH
  if ($epoch -lt 315532800) { $epoch = 315532800 }
  if ($epoch -gt 4354819198) { $epoch = 4354819198 }
  $epoch = $epoch - ($epoch % 2)
  $timestamp = [DateTimeOffset]::FromUnixTimeSeconds($epoch)
  $zip = [IO.Compression.ZipFile]::Open($archive, [IO.Compression.ZipArchiveMode]::Create)
  try {
    $entries = @(Get-ChildItem -LiteralPath $stagePlugin -Recurse -File | ForEach-Object {
      [PSCustomObject]@{ File = $_; Relative = $_.FullName.Substring($stagePlugin.Length).TrimStart('\', '/') -replace '\\', '/' }
    } | Sort-Object Relative)
    foreach ($item in $entries) {
      if ($item.Relative.StartsWith('/') -or $item.Relative -match '(^|/)\.\.(/|$)') { throw 'Unsafe package entry path' }
      $entry = $zip.CreateEntry("dm7-database/$($item.Relative)", [IO.Compression.CompressionLevel]::Optimal)
      $entry.LastWriteTime = $timestamp
      $input = [IO.File]::OpenRead($item.File.FullName)
      $output = $entry.Open()
      try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
    }
  } finally { $zip.Dispose() }
  $archiveScan = Join-Path $stageRoot 'archive-scan'
  New-Item -ItemType Directory -Force -Path $archiveScan | Out-Null
  Copy-Item -LiteralPath $archive -Destination (Join-Path $archiveScan 'release.zip')
  python (Join-Path $PSScriptRoot 'verify-package-security.py') $archiveScan
  if ($LASTEXITCODE -ne 0) { throw 'Final archive recursive security scan failed' }
  & (Join-Path $PSScriptRoot 'verify-extracted.ps1') -Archive $archive -RepoRoot $repoRoot
  Write-Output $archive
} finally {
  if (Test-Path -LiteralPath $stageRoot) { Remove-Item -LiteralPath $stageRoot -Recurse -Force }
  Pop-Location
}
