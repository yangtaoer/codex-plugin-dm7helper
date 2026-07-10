$ErrorActionPreference = 'Stop'

$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $pluginRoot '..\..')).Path

if ([string]::IsNullOrWhiteSpace($env:SOURCE_DATE_EPOCH)) {
  $env:SOURCE_DATE_EPOCH = (git -C $repoRoot log -1 --format=%ct).Trim()
}
if ([string]::IsNullOrWhiteSpace($env:SOURCE_DATE_EPOCH)) {
  throw 'SOURCE_DATE_EPOCH could not be derived from the current commit'
}

pnpm --dir (Join-Path $pluginRoot 'web') build
if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }

mvn -f (Join-Path $pluginRoot 'server\pom.xml') clean package
if ($LASTEXITCODE -ne 0) { throw 'Server build failed' }
