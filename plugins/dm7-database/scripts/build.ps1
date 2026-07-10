$ErrorActionPreference = 'Stop'

$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $pluginRoot '..\..')).Path

function Resolve-PnpmCommand {
  $command = Get-Command pnpm.cmd -ErrorAction SilentlyContinue
  if (-not $command) { $command = Get-Command pnpm -ErrorAction SilentlyContinue }
  if ($command) { return $command.Source }

  $runtimeRoot = Join-Path $env:USERPROFILE '.cache\codex-runtimes'
  if (Test-Path -LiteralPath $runtimeRoot) {
    $candidate = Get-ChildItem -Path (Join-Path $runtimeRoot '*\dependencies\bin\fallback\pnpm.cmd') -File |
      Sort-Object LastWriteTimeUtc -Descending |
      Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
  }
  throw 'pnpm was not found on PATH or in the Codex runtime cache'
}

if ([string]::IsNullOrWhiteSpace($env:SOURCE_DATE_EPOCH)) {
  $env:SOURCE_DATE_EPOCH = (git -C $repoRoot log -1 --format=%ct).Trim()
}
if ([string]::IsNullOrWhiteSpace($env:SOURCE_DATE_EPOCH)) {
  throw 'SOURCE_DATE_EPOCH could not be derived from the current commit'
}

$pnpm = Resolve-PnpmCommand
Push-Location $repoRoot
try {
  & $pnpm --dir (Join-Path $pluginRoot 'web') install --frozen-lockfile
  if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed' }

  & $pnpm --dir (Join-Path $pluginRoot 'web') build
  if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }

  mvn -f (Join-Path $pluginRoot 'server\pom.xml') clean package
  if ($LASTEXITCODE -ne 0) { throw 'Server build failed' }
} finally {
  Pop-Location
}
