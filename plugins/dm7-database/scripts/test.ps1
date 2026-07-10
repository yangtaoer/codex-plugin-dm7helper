$ErrorActionPreference = 'Stop'

$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $pluginRoot '..\..')).Path

python -m unittest tests.plugin_layout_test -v
if ($LASTEXITCODE -ne 0) { throw 'Repository layout test failed' }

mvn -f (Join-Path $pluginRoot 'server\pom.xml') verify
if ($LASTEXITCODE -ne 0) { throw 'Server tests failed' }

pnpm --dir (Join-Path $pluginRoot 'web') check
if ($LASTEXITCODE -ne 0) { throw 'Frontend checks failed' }
