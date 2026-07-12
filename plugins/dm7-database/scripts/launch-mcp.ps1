$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$diagnosticFile = $env:DM7_MCP_DIAGNOSTIC_FILE
if (-not $diagnosticFile) {
  try { $diagnosticFile = Join-Path ([IO.Path]::GetTempPath()) 'dm7-mcp-launcher-status.log' }
  catch { $diagnosticFile = $null }
}
function Write-LaunchStatus([string]$status) {
  if (-not $diagnosticFile) { return }
  try { [IO.File]::WriteAllText($diagnosticFile, $status + "`n", [Text.UTF8Encoding]::new($false)) }
  catch { }
}

$pluginRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$jar = Join-Path $pluginRoot 'lib\dm7-codex-plugin.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
  Write-LaunchStatus 'JAR_MISSING'
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

$searchRoots = [System.Collections.Generic.List[string]]::new()
if ($env:DM7_CODEX_JAVA_SEARCH_ROOTS) {
  $env:DM7_CODEX_JAVA_SEARCH_ROOTS -split [IO.Path]::PathSeparator | ForEach-Object {
    if ($_) { $searchRoots.Add($_) }
  }
}
if ($env:ProgramFiles) {
  $searchRoots.Add((Join-Path $env:ProgramFiles 'Java'))
  $searchRoots.Add((Join-Path $env:ProgramFiles 'Eclipse Adoptium'))
  $searchRoots.Add((Join-Path $env:ProgramFiles 'Microsoft'))
}
$searchRoots.Add('C:\tool')
foreach ($root in $searchRoots) {
  if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
  Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $candidates.Add((Join-Path $_.FullName 'bin\java.exe'))
  }
}

foreach ($registryRoot in @('HKLM:\SOFTWARE\JavaSoft\JDK', 'HKLM:\SOFTWARE\JavaSoft\Java Development Kit')) {
  if (-not (Test-Path $registryRoot)) { continue }
  Get-ChildItem $registryRoot -ErrorAction SilentlyContinue | ForEach-Object {
    $javaHomeCandidate = (Get-ItemProperty $_.PSPath -Name JavaHome -ErrorAction SilentlyContinue).JavaHome
    if ($javaHomeCandidate) { $candidates.Add((Join-Path $javaHomeCandidate 'bin\java.exe')) }
  }
}

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
  Write-LaunchStatus 'JAVA_17_NOT_FOUND'
  [Console]::Error.WriteLine('DM7 MCP requires Java 17 or newer. Set DM7_CODEX_JAVA or update PATH.')
  exit 3
}

$env:PLUGIN_ROOT = $pluginRoot
Write-LaunchStatus 'JAVA_SELECTED_AND_STARTING'
& $selected '-Dfile.encoding=UTF-8' '-jar' $jar '--stdio'
Write-LaunchStatus ("JAVA_EXIT_" + $LASTEXITCODE)
exit $LASTEXITCODE
