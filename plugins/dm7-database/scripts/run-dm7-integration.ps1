$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$required = @('DM7_IT_JDBC_URL', 'DM7_IT_USERNAME', 'DM7_IT_PASSWORD', 'DM7_IT_DRIVER_JAR')
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverRoot = Join-Path $pluginRoot 'server'
$candidate = Join-Path $serverRoot 'target\dm7-integration-candidate.json'
$cleanupManifest = Join-Path $serverRoot 'target\dm7-integration-cleanup-manifest.json'
$schema = Join-Path $repoRoot 'artifacts\acceptance\dm7-integration-summary.schema.json'
$summary = Join-Path $repoRoot 'artifacts\acceptance\dm7-integration-summary.json'

function Resolve-CommandPath([string[]]$Names, [string[]]$Fallbacks) {
  foreach ($name in $Names) { $command = Get-Command $name -ErrorAction SilentlyContinue; if ($command) { return $command.Source } }
  foreach ($fallback in $Fallbacks) { if ($fallback -and (Test-Path -LiteralPath $fallback -PathType Leaf)) { return $fallback } }
  throw 'Integration toolchain is unavailable'
}

function Assert-NoExactValuesInText([string]$Text, [string[]]$Values) {
  foreach ($value in $Values) { if ($Text.Contains($value)) { throw 'An integration environment value was persisted to an artifact' } }
}

$values = @($required | ForEach-Object { [Environment]::GetEnvironmentVariable($_) })
try {
  if (@($values | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -ne 4) {
    throw 'Runner requires exactly four integration environment variables'
  }
  $javaHome = if ($env:DM7_CODEX_JAVA_HOME) { $env:DM7_CODEX_JAVA_HOME } else { 'C:\tool\jdk21' }
  if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\javac.exe'))) { throw 'JDK 21 is required for integration acceptance' }
  $env:JAVA_HOME = $javaHome; $env:PATH = (Join-Path $javaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH
  $javacVersion = (& (Join-Path $javaHome 'bin\javac.exe') -version 2>&1 | Out-String).Trim()
  if ($javacVersion -notmatch '^javac 21(?:\.|$)') { throw 'JDK 21 is required for integration acceptance' }
  $maven = Resolve-CommandPath @('mvn.cmd','mvn') @('C:\tool\apache-maven-3.9.16\bin\mvn.cmd')
  if (Test-Path -LiteralPath $candidate) { Remove-Item -LiteralPath $candidate -Force }
  if (Test-Path -LiteralPath $cleanupManifest) { Remove-Item -LiteralPath $cleanupManifest -Force }

  $mavenArguments = @('-q','-f',(Join-Path $serverRoot 'pom.xml'),'-Pintegration','verify',
    "-Ddm7.integration.candidate=$candidate","-Ddm7.integration.cleanup-manifest=$cleanupManifest",'-Dstyle.color=never')
  $mavenOutput = (& $maven @mavenArguments 2>&1 | Out-String)
  $mavenExitCode = $LASTEXITCODE

  $scanRoots = @(
    (Join-Path $serverRoot 'target'), (Join-Path $serverRoot 'src'),
    (Join-Path $pluginRoot 'lib'), (Join-Path $pluginRoot 'assets'), (Join-Path $pluginRoot 'hooks'),
    (Join-Path $pluginRoot 'docs'), (Join-Path $pluginRoot 'licenses'), (Join-Path $pluginRoot 'scripts'),
    (Join-Path $pluginRoot 'skills'), (Join-Path $pluginRoot 'web\src'), (Join-Path $pluginRoot 'web\dist'),
    (Join-Path $pluginRoot 'web\test-results'), (Join-Path $repoRoot 'artifacts'), (Join-Path $repoRoot 'dist'),
    (Join-Path $repoRoot '.superpowers\sdd')
  ) | Where-Object { Test-Path -LiteralPath $_ }
  foreach ($root in $scanRoots) {
    python (Join-Path $PSScriptRoot 'verify-package-security.py') $root | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'An integration environment value was persisted to an artifact' }
  }
  Assert-NoExactValuesInText ((git -C $repoRoot diff --binary HEAD | Out-String)) $values
  Assert-NoExactValuesInText ((git -C $repoRoot diff --cached --binary | Out-String)) $values
  $indexRoot = Join-Path $serverRoot "target\staged-index-$([Guid]::NewGuid().ToString('N'))"
  try {
    New-Item -ItemType Directory -Force -Path $indexRoot | Out-Null
    $prefix = $indexRoot + [IO.Path]::DirectorySeparatorChar
    git -C $repoRoot checkout-index --all "--prefix=$prefix" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Staged blob materialization failed' }
    python (Join-Path $PSScriptRoot 'verify-package-security.py') $indexRoot | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'A staged blob contains an integration environment value' }
  } finally { if (Test-Path -LiteralPath $indexRoot) { Remove-Item -LiteralPath $indexRoot -Recurse -Force } }

  if ($mavenExitCode -ne 0) { throw 'DM7 integration profile failed; inspect sanitized local test diagnostics' }
  if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw 'Sanitized integration candidate was not produced' }
  if (Test-Path -LiteralPath $cleanupManifest) { throw 'Cleanup manifest remained after independent verification' }

  $driverHash = (Get-FileHash -LiteralPath $values[3] -Algorithm SHA256).Hash
  foreach ($jar in Get-ChildItem -LiteralPath $repoRoot -Recurse -File -Filter '*.jar' | Where-Object {
      $_.FullName -notmatch '[\\/]node_modules[\\/]'
    }) {
    if ((Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash -eq $driverHash) {
      throw 'The vendor JDBC driver was copied into the repository'
    }
  }

  python (Join-Path $PSScriptRoot 'validate-integration-summary.py') $schema $candidate | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Sanitized integration candidate schema validation failed' }
  $report = Get-Content -LiteralPath $candidate -Raw -Encoding UTF8
  Assert-NoExactValuesInText $report $values
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $summary) | Out-Null
  $temporarySummary = Join-Path (Split-Path -Parent $summary) ".dm7-integration-$([Guid]::NewGuid().ToString('N')).tmp"
  $backupSummary = Join-Path (Split-Path -Parent $summary) ".dm7-integration-$([Guid]::NewGuid().ToString('N')).bak"
  try {
    [IO.File]::WriteAllText($temporarySummary, $report, (New-Object Text.UTF8Encoding($false)))
    if (Test-Path -LiteralPath $summary) { [IO.File]::Replace($temporarySummary, $summary, $backupSummary) }
    else { [IO.File]::Move($temporarySummary, $summary) }
  } finally {
    if (Test-Path -LiteralPath $temporarySummary) { Remove-Item -LiteralPath $temporarySummary -Force }
    if (Test-Path -LiteralPath $backupSummary) { Remove-Item -LiteralPath $backupSummary -Force }
  }
  python (Join-Path $PSScriptRoot 'validate-integration-summary.py') $schema $summary | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Published integration summary schema validation failed' }
  Write-Output 'DM7 integration acceptance passed with sanitized evidence'
} finally {
  $required | ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
}
