$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:PLUGIN_DATA)) { exit 0 }
$sessionId = if (-not [string]::IsNullOrWhiteSpace($env:CODEX_SESSION_ID)) { $env:CODEX_SESSION_ID } else { $env:CODEX_THREAD_ID }
if ([string]::IsNullOrWhiteSpace($sessionId)) { exit 0 }
$threadValue = if ([string]::IsNullOrWhiteSpace($env:CODEX_THREAD_ID)) { '' } else { $env:CODEX_THREAD_ID }

function Get-Sha256([string]$Value) {
  $algorithm = [Security.Cryptography.SHA256]::Create()
  try { return -join ($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)) | ForEach-Object { $_.ToString('x2') }) }
  finally { $algorithm.Dispose() }
}

$sessionHash = Get-Sha256 $sessionId
$threadHash = Get-Sha256 $threadValue
$contextDirectory = Join-Path $env:PLUGIN_DATA 'session-context'
New-Item -ItemType Directory -Force -Path $contextDirectory | Out-Null
$lockPath = Join-Path $contextDirectory '.context.lock'
$lock = $null
for ($attempt = 0; $attempt -lt 200 -and -not $lock; $attempt++) {
  try { $lock = New-Object IO.FileStream($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
  catch [IO.IOException] { Start-Sleep -Milliseconds 20 }
}
if (-not $lock) { throw 'Unable to acquire the session-context lock' }

try {
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
  $directorySecurity = New-Object Security.AccessControl.DirectorySecurity
  $directorySecurity.SetAccessRuleProtection($true, $false)
  $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
  $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, [Security.AccessControl.FileSystemRights]::FullControl, $inheritance, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
  $directorySecurity.AddAccessRule($rule)
  [IO.Directory]::SetAccessControl($contextDirectory, $directorySecurity)

  $context = [ordered]@{
    sessionHash = $sessionHash
    timestamp = [DateTimeOffset]::UtcNow.ToString('O')
    processThreadHash = $threadHash
  }
  $contextPath = Join-Path $contextDirectory "$sessionHash.json"
  $temporaryPath = Join-Path $contextDirectory ".$sessionHash.$([Guid]::NewGuid().ToString('N')).tmp"
  try {
    [IO.File]::WriteAllText($temporaryPath, ($context | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporaryPath -Destination $contextPath -Force
  } finally {
    if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
  }
} finally {
  $lock.Dispose()
}
