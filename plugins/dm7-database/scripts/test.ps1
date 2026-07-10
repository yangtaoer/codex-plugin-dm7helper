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

$pnpm = Resolve-PnpmCommand
$validator = Join-Path $env:USERPROFILE '.codex\skills\.system\plugin-creator\scripts\validate_plugin.py'

Push-Location $repoRoot
try {
  python -m unittest tests.plugin_layout_test tests.plugin_scripts_test -v
  if ($LASTEXITCODE -ne 0) { throw 'Repository regression tests failed' }

  python $validator $pluginRoot
  if ($LASTEXITCODE -ne 0) { throw 'Plugin validation failed' }

  mvn -f (Join-Path $pluginRoot 'server\pom.xml') verify
  if ($LASTEXITCODE -ne 0) { throw 'Server tests failed' }

  & $pnpm --dir (Join-Path $pluginRoot 'web') check
  if ($LASTEXITCODE -ne 0) { throw 'Frontend checks failed' }
} finally {
  Pop-Location
}
