$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $pluginRoot '..\..')).Path

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

function Resolve-PnpmCommand {
  $fallbacks = @()
  $runtimeRoot = Join-Path $env:USERPROFILE '.cache\codex-runtimes'
  if (Test-Path -LiteralPath $runtimeRoot) {
    $fallbacks = @(Get-ChildItem -Path (Join-Path $runtimeRoot '*\dependencies\bin\fallback\pnpm.cmd') -File |
      Sort-Object LastWriteTimeUtc -Descending | ForEach-Object FullName)
  }
  return Resolve-CommandPath @('pnpm.cmd', 'pnpm') $fallbacks
}

function Initialize-Node {
  if (Get-Command node.exe -ErrorAction SilentlyContinue) { return }
  if (Get-Command node -ErrorAction SilentlyContinue) { return }
  $runtimeRoot = Join-Path $env:USERPROFILE '.cache\codex-runtimes'
  $node = Get-ChildItem -Path (Join-Path $runtimeRoot '*\dependencies\node\bin\node.exe') -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
  if (-not $node) { throw 'Node.js was not found on PATH or in the Codex runtime cache' }
  $env:PATH = $node.DirectoryName + [IO.Path]::PathSeparator + $env:PATH
}

Initialize-Java
Initialize-Node
$pnpm = Resolve-PnpmCommand
$maven = Resolve-CommandPath @('mvn.cmd', 'mvn') @('C:\tool\apache-maven-3.9.16\bin\mvn.cmd')
if ([string]::IsNullOrWhiteSpace($env:SOURCE_DATE_EPOCH)) {
  $env:SOURCE_DATE_EPOCH = (git -C $repoRoot log -1 --format=%ct).Trim()
}
if ($env:SOURCE_DATE_EPOCH -notmatch '^\d+$') { throw 'SOURCE_DATE_EPOCH must be a Unix timestamp' }

Push-Location $repoRoot
try {
  & $pnpm --dir (Join-Path $pluginRoot 'web') install --frozen-lockfile
  if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed' }
  & $pnpm --dir (Join-Path $pluginRoot 'web') build
  if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
  & $maven -f (Join-Path $pluginRoot 'server\pom.xml') clean package
  if ($LASTEXITCODE -ne 0) { throw 'Server clean package failed' }
} finally { Pop-Location }
