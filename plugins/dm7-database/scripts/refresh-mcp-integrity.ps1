$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$pluginRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$manifestPath = Join-Path $pluginRoot '.codex-plugin\plugin.json'
$mcpPath = Join-Path $pluginRoot '.mcp.json'
$launcherPath = Join-Path $pluginRoot 'scripts\launch-mcp.ps1'
$jarPath = Join-Path $pluginRoot 'lib\dm7-codex-plugin.jar'

foreach ($required in @($manifestPath, $mcpPath, $launcherPath, $jarPath)) {
  if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required plugin file is missing: $required" }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($manifest.version)) { throw 'Plugin manifest version is missing.' }
$config = Get-Content -LiteralPath $mcpPath -Raw -Encoding UTF8 | ConvertFrom-Json
$args = @($config.mcpServers.dm7.args)
if ($args.Count -lt 1 -or $args[-2] -ne '-EncodedCommand') { throw 'DM7 MCP bootstrap is not an encoded PowerShell command.' }

$bootstrap = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String([string]$args[-1]))
function Set-Assignment([string]$Source, [string]$Name, [string]$Value) {
  $pattern = '(?m)^\$' + [regex]::Escape($Name) + "\s*=\s*'[^']*'\s*$"
  if ([regex]::Matches($Source, $pattern).Count -ne 1) { throw "Bootstrap assignment is missing or ambiguous: $Name" }
  return [regex]::Replace($Source, $pattern, ('$' + $Name + " = '" + $Value + "'"))
}

$bootstrap = Set-Assignment $bootstrap 'expectedVersion' ([string]$manifest.version)
$bootstrap = Set-Assignment $bootstrap 'expectedLauncher' ((Get-FileHash -LiteralPath $launcherPath -Algorithm SHA256).Hash)
$bootstrap = Set-Assignment $bootstrap 'expectedJar' ((Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash)
$args[-1] = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($bootstrap))
$config.mcpServers.dm7.args = $args

$json = $config | ConvertTo-Json -Depth 16
[IO.File]::WriteAllText($mcpPath, $json + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "Refreshed MCP integrity metadata for $($manifest.version)."
