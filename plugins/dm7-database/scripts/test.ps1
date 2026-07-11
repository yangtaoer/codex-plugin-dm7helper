$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $pluginRoot '..\..')).Path

function Initialize-Java {
  $pathJavac = Get-Command javac.exe -ErrorAction SilentlyContinue
  if (-not $pathJavac) { $pathJavac = Get-Command javac -ErrorAction SilentlyContinue }
  $pathHome = if ($pathJavac) { Split-Path -Parent (Split-Path -Parent $pathJavac.Source) } else { $null }
  $homes = @($env:DM7_CODEX_JAVA_HOME, $env:CODEX_JAVA_HOME, $env:JAVA_HOME, $pathHome, 'C:\tool\jdk21') |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
  foreach ($jdkHome in $homes) {
    $java = Join-Path $jdkHome 'bin\java.exe'
    $javac = Join-Path $jdkHome 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $javac)) { continue }
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $javac; $start.Arguments = '-version'; $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true; $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($start)
    $version = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd(); $process.WaitForExit()
    if ($process.ExitCode -eq 0 -and $version -match '^javac\s+(\d+)' -and [int]$Matches[1] -ge 17) {
      $env:JAVA_HOME = $jdkHome
      $env:PATH = (Join-Path $jdkHome 'bin') + [IO.Path]::PathSeparator + $env:PATH
      return
    }
  }
  throw 'A JDK 17 or newer is required; set JAVA_HOME (JDK 21 is used for development)'
}

Initialize-Java

if (-not (Get-Command node.exe -ErrorAction SilentlyContinue) -and -not (Get-Command node -ErrorAction SilentlyContinue)) {
  $node = Get-ChildItem -Path (Join-Path $env:USERPROFILE '.cache\codex-runtimes\*\dependencies\node\bin\node.exe') -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
  if (-not $node) { throw 'Node.js was not found on PATH or in the Codex runtime cache' }
  $env:PATH = $node.DirectoryName + [IO.Path]::PathSeparator + $env:PATH
}

function Resolve-CommandPath([string[]]$Names, [string[]]$Fallbacks) {
  foreach ($name in $Names) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
  }
  foreach ($candidate in $Fallbacks) {
    if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $candidate }
  }
  throw "Required command was not found: $($Names -join ', ')"
}

$pnpmFallbacks = @()
$runtimeRoot = Join-Path $env:USERPROFILE '.cache\codex-runtimes'
if (Test-Path -LiteralPath $runtimeRoot) {
  $pnpmFallbacks = @(Get-ChildItem -Path (Join-Path $runtimeRoot '*\dependencies\bin\fallback\pnpm.cmd') -File |
    Sort-Object LastWriteTimeUtc -Descending | ForEach-Object FullName)
}
$pnpm = Resolve-CommandPath @('pnpm.cmd', 'pnpm') $pnpmFallbacks
$maven = Resolve-CommandPath @('mvn.cmd', 'mvn') @('C:\tool\apache-maven-3.9.16\bin\mvn.cmd')
$validator = Join-Path $env:USERPROFILE '.codex\skills\.system\plugin-creator\scripts\validate_plugin.py'

Push-Location $repoRoot
try {
  python -m unittest tests.plugin_layout_test tests.plugin_scripts_test -v
  if ($LASTEXITCODE -ne 0) { throw 'Repository regression tests failed' }
  python $validator $pluginRoot
  if ($LASTEXITCODE -ne 0) { throw 'Plugin validation failed' }
  & $pnpm --dir (Join-Path $pluginRoot 'web') install --frozen-lockfile
  if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed' }
  & $pnpm --dir (Join-Path $pluginRoot 'web') build
  if ($LASTEXITCODE -ne 0) { throw 'Frontend production build failed' }
  & $maven -f (Join-Path $pluginRoot 'server\pom.xml') verify
  if ($LASTEXITCODE -ne 0) { throw 'Server tests failed' }
  python -m unittest tests.web_assets_test -v # web_assets_test.py verifies web/dist and the fat JAR
  if ($LASTEXITCODE -ne 0) { throw 'Production web asset tests failed' }
  & $pnpm --dir (Join-Path $pluginRoot 'web') check
  if ($LASTEXITCODE -ne 0) { throw 'Frontend checks failed' }
  & $pnpm --dir (Join-Path $pluginRoot 'web') e2e
  if ($LASTEXITCODE -ne 0) { throw 'Frontend end-to-end tests failed' }
  python (Join-Path $repoRoot 'tests\mcp_stdio_smoke.py')
  if ($LASTEXITCODE -ne 0) { throw 'MCP STDIO smoke test failed' }
} finally { Pop-Location }
