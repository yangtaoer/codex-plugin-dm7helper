$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$pluginRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$jar = Join-Path $pluginRoot 'lib\dm7-codex-plugin.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
  [Console]::Error.WriteLine('DM7 MCP runtime JAR is missing.')
  exit 2
}

$candidates = [System.Collections.Generic.List[string]]::new()
if ($env:DM7_CODEX_JAVA) { $candidates.Add($env:DM7_CODEX_JAVA) }
if ($env:DM7_CODEX_JAVA_HOME) { $candidates.Add((Join-Path $env:DM7_CODEX_JAVA_HOME 'bin\java.exe')) }
if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
try {
  Get-Command java.exe -All -ErrorAction Stop | ForEach-Object { $candidates.Add($_.Source) }
} catch { }

$selected = $null
$seen = @{}
foreach ($candidate in $candidates) {
  if (-not $candidate) { continue }
  try { $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path } catch { continue }
  $key = $resolved.ToLowerInvariant()
  if ($seen.ContainsKey($key)) { continue }
  $seen[$key] = $true
  try {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $resolved
    $start.Arguments = '-version'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) { continue }
    $versionText = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { continue }
    $match = [regex]::Match($versionText, 'version\s+"(?<major>[0-9]+)')
    if ($match.Success -and [int]$match.Groups['major'].Value -ge 17) {
      $selected = $resolved
      break
    }
  } catch { continue }
}

if (-not $selected) {
  [Console]::Error.WriteLine('DM7 MCP requires Java 17 or newer. Set DM7_CODEX_JAVA or update PATH.')
  exit 3
}

$env:PLUGIN_ROOT = $pluginRoot
& $selected '-Dfile.encoding=UTF-8' '-jar' $jar '--stdio'
exit $LASTEXITCODE
