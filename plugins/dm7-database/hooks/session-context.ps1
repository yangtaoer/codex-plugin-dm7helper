$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:PLUGIN_DATA)) {
  exit 0
}

$sessionId = $env:CODEX_THREAD_ID
if ([string]::IsNullOrWhiteSpace($sessionId)) {
  exit 0
}

$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
  $sessionHash = [Convert]::ToHexString($sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($sessionId))).ToLowerInvariant()
  $threadValue = if ($env:CODEX_THREAD_ID) { $env:CODEX_THREAD_ID } else { 'unavailable' }
  $threadHash = [Convert]::ToHexString($sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($threadValue))).ToLowerInvariant()
} finally {
  $sha256.Dispose()
}

$contextDirectory = Join-Path $env:PLUGIN_DATA 'session-context'
New-Item -ItemType Directory -Force -Path $contextDirectory | Out-Null
$context = [ordered]@{
  sessionHash = $sessionHash
  timestamp = [DateTimeOffset]::UtcNow.ToString('O')
  processThreadHash = $threadHash
}
$contextPath = Join-Path $contextDirectory "$sessionHash.json"
[IO.File]::WriteAllText($contextPath, ($context | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
