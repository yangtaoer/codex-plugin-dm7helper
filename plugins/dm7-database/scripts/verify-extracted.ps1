param(
  [string]$Archive,
  [string]$RepoRoot,
  [switch]$CheckJava17Only
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

function Resolve-Java17Home {
  $pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
  if (-not $pathJava) { $pathJava = Get-Command java -ErrorAction SilentlyContinue }
  $pathHome = if ($pathJava) { Split-Path -Parent (Split-Path -Parent $pathJava.Source) } else { $null }
  $homes = @($env:DM7_CODEX_JAVA17_HOME, $env:CODEX_JAVA17_HOME, $env:JAVA_HOME, $pathHome) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
  foreach ($candidateHome in $homes) {
    $java = Join-Path $candidateHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { continue }
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $java; $start.Arguments = '-version'; $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true; $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($start)
    $version = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd(); $process.WaitForExit()
    if ($process.ExitCode -eq 0 -and $version -match 'version\s+"17(?:\.|\+)' ) { return $candidateHome }
  }
  throw 'Fresh-package verification requires an exact Java 17 runtime; set DM7_CODEX_JAVA17_HOME'
}

$java17Home = Resolve-Java17Home
if ($CheckJava17Only) { Write-Output 'Exact Java 17 runtime is available'; return }
if ([string]::IsNullOrWhiteSpace($Archive) -or [string]::IsNullOrWhiteSpace($RepoRoot)) {
  throw 'Archive and RepoRoot are required for fresh-package verification'
}
$verificationRoot = Join-Path $env:TEMP "DM7 插件 验证 $([Guid]::NewGuid().ToString('N'))"
try {
  New-Item -ItemType Directory -Path $verificationRoot | Out-Null
  Expand-Archive -LiteralPath $Archive -DestinationPath $verificationRoot
  $plugin = Join-Path $verificationRoot 'dm7-database'
  foreach ($forbidden in @('server', 'web', 'scripts', 'node_modules', 'target', 'test-results')) {
    if (Test-Path -LiteralPath (Join-Path $plugin $forbidden)) { throw 'Fresh package contains source or build directories' }
  }

  $validator = if ($env:DM7_CODEX_PLUGIN_VALIDATOR) { $env:DM7_CODEX_PLUGIN_VALIDATOR } else {
    Join-Path $env:USERPROFILE '.codex\skills\.system\plugin-creator\scripts\validate_plugin.py'
  }
  python $validator $plugin
  if ($LASTEXITCODE -ne 0) { throw 'Fresh-package plugin validation failed' }

  $savedJavaHome = $env:JAVA_HOME; $savedSmokeRoot = $env:DM7_SMOKE_PLUGIN_ROOT
  try {
    $env:JAVA_HOME = $java17Home
    $env:DM7_SMOKE_PLUGIN_ROOT = $plugin
    python (Join-Path $RepoRoot 'tests\mcp_stdio_smoke.py')
    if ($LASTEXITCODE -ne 0) { throw 'Fresh-package Java 17 MCP smoke failed' }
  } finally {
    $env:JAVA_HOME = $savedJavaHome; $env:DM7_SMOKE_PLUGIN_ROOT = $savedSmokeRoot
  }

  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $jar = [IO.Compression.ZipFile]::OpenRead((Join-Path $plugin 'lib\dm7-codex-plugin.jar'))
  try {
    $entry = $jar.GetEntry('io/dm7codex/plugin/AppMain.class')
    if (-not $entry) { throw 'Runtime entry point is missing' }
    $stream = $entry.Open(); $header = New-Object byte[] 8
    try { if ($stream.Read($header, 0, 8) -ne 8) { throw 'Runtime class header is truncated' } } finally { $stream.Dispose() }
    $major = ([int]$header[6] -shl 8) -bor [int]$header[7]
    if ($major -ne 61) { throw 'Runtime bytecode is not Java 17' }
  } finally { $jar.Dispose() }

  $hookData = Join-Path $verificationRoot 'hook data'
  $savedPluginData = $env:PLUGIN_DATA; $savedSession = $env:CODEX_SESSION_ID; $savedModulePath = $env:PSModulePath
  try {
    $env:PLUGIN_DATA = $hookData; $env:CODEX_SESSION_ID = 'package-verification-session'
    Remove-Item Env:PSModulePath -ErrorAction SilentlyContinue
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $plugin 'hooks\session-context.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Fresh-package hook execution failed' }
    $contextDirectory = Join-Path $hookData 'session-context'
    $acl = powershell.exe -NoProfile -Command "[IO.Directory]::GetAccessControl('$($contextDirectory.Replace("'", "''"))').AreAccessRulesProtected"
    if ($LASTEXITCODE -ne 0 -or ($acl | Out-String).Trim() -ne 'True') { throw 'Fresh-package hook ACL verification failed' }
    if (Test-Path -LiteralPath (Join-Path $hookData 'release')) { throw 'Fresh-package hook created release state eagerly' }
  } finally {
    $env:PLUGIN_DATA = $savedPluginData; $env:CODEX_SESSION_ID = $savedSession; $env:PSModulePath = $savedModulePath
  }
  Write-Output 'Fresh extracted Java 17 verification passed'
} finally {
  if (Test-Path -LiteralPath $verificationRoot) {
    $resolved = (Resolve-Path $verificationRoot).Path; $temp = (Resolve-Path $env:TEMP).Path
    if (-not $resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe verification cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
  }
}
