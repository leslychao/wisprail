$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$request = [Console]::In.ReadToEnd() | ConvertFrom-Json
$executable = [System.IO.Path]::GetFullPath([string]$request.executable)
$name = [System.IO.Path]::GetFileName($executable)
if ($name -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Invalid executable name' }
$processes = @(
  Get-CimInstance -ClassName Win32_Process -Filter "Name='$name'" | ForEach-Object {
    if ([string]::IsNullOrEmpty($_.ExecutablePath) -or $_.ExecutablePath -eq $executable) {
      $identified = -not [string]::IsNullOrEmpty($_.ExecutablePath) -and
          -not [string]::IsNullOrEmpty($_.CommandLine) -and $null -ne $_.CreationDate
      @{
        pid = [long]$_.ProcessId
        identified = $identified
        started = if ($identified) { ([DateTimeOffset]$_.CreationDate).ToUnixTimeMilliseconds() } else { 0 }
        command = if ($identified) { [string]$_.CommandLine } else { '' }
      }
    }
  }
)
@{ processes = $processes } | ConvertTo-Json -Depth 4 -Compress
