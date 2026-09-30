param([Parameter(Mandatory = $true)][string]$Archive)
$ErrorActionPreference = 'Stop'
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$output = Join-Path $PSScriptRoot ('target\acceptance\windows-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $output | Out-Null
Expand-Archive -LiteralPath $archivePath -DestinationPath (Join-Path $output 'extracted')
$app = Join-Path $output 'extracted\Wisprail'
$info = Get-Content -LiteralPath (Join-Path $app 'build-info.json') -Raw | ConvertFrom-Json
if ([IO.Path]::GetFileName($archivePath) -ne "wisprail-$($info.version)-windows-x64.zip") { throw 'Archive name and version do not match.' }
if ($info.platform -ne 'windows' -or $info.architecture -ne 'x64' -or $info.engineVersion -ne '1.14.2') {
    throw 'Unexpected package metadata.'
}
foreach ($file in @('Wisprail.exe', 'WisprailAgent.exe', 'runtime\bin\java.exe', 'app\engine\sing-box.exe',
    'app\install-service.ps1', 'app\uninstall-service.ps1', 'app\THIRD_PARTY_NOTICES.md', 'app\engine\LICENSE')) {
    if (-not (Test-Path -LiteralPath (Join-Path $app $file) -PathType Leaf)) { throw "Missing distribution file: $file" }
}
$runtimeBinaries = @(Get-ChildItem -LiteralPath (Join-Path $app 'runtime') -Recurse -File | Where-Object { $_.Extension -in '.dll', '.exe' })
foreach ($binary in $runtimeBinaries) {
    if ((Get-AuthenticodeSignature -LiteralPath $binary.FullName).Status -ne 'Valid') {
        throw "Runtime signature invalid: $($binary.Name)"
    }
}
$engine = & (Join-Path $app 'app\engine\sing-box.exe') version
if ($LASTEXITCODE -ne 0 -or ($engine -join "`n") -notmatch 'sing-box version 1\.14\.2') { throw 'Engine version check failed.' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Launch acceptance must run without elevation.' }
$start = Get-Date
$launch = New-Object Diagnostics.ProcessStartInfo
$launch.FileName = Join-Path $app 'Wisprail.exe'
$launch.WorkingDirectory = $app
$launch.UseShellExecute = $false
$launch.EnvironmentVariables['LOCALAPPDATA'] = Join-Path $output 'user-data'
$launch.EnvironmentVariables.Remove('JAVA_HOME')
$launch.EnvironmentVariables['PATH'] = Join-Path $env:SystemRoot 'System32'
$javaHomeDirectory = Join-Path $output 'java-user-home'
$nativeTemporaryDirectory = Join-Path $output 'native-temp'
New-Item -ItemType Directory -Path $javaHomeDirectory, $nativeTemporaryDirectory | Out-Null
# Exercise extraction from JARs into fresh directories instead of reusing an existing native cache.
$launch.EnvironmentVariables['JAVA_TOOL_OPTIONS'] = '-Duser.home="' + $javaHomeDirectory + '" -Djna.tmpdir="' + $nativeTemporaryDirectory + '"'
$launcher = [Diagnostics.Process]::Start($launch)
$process = $null
try {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $process = Get-Process Wisprail -ErrorAction SilentlyContinue | Where-Object {
            $_.Path -eq $launch.FileName -and $_.MainWindowHandle -ne 0
        } | Select-Object -First 1
        if ($null -eq $process) { Start-Sleep -Milliseconds 250 }
    } while ($null -eq $process -and (Get-Date) -lt $deadline)
    if ($null -eq $process) { throw 'The extracted application did not open a window.' }
    Start-Sleep -Milliseconds 1500
    $modules = @($process.Modules | ForEach-Object { $_.FileName })
    $modules | Set-Content -LiteralPath (Join-Path $output 'loaded-modules.txt') -Encoding UTF8
    foreach ($name in @('fontmanager.dll', 'glass.dll', 'javafx_font.dll')) {
        if (-not ($modules | Where-Object { [IO.Path]::GetFileName($_) -eq $name })) { throw "Native module not observed: $name" }
    }
    if (-not ($modules | Where-Object { $_ -match '\\jna[^\\]*\.dll$' })) { throw 'JNA native module not observed.' }
    Add-Type -AssemblyName System.Drawing
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WisprailCapture {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
"@
    [WisprailCapture]::SetForegroundWindow($process.MainWindowHandle) | Out-Null
    Start-Sleep -Milliseconds 250
    $screenshot = 'NOT_RUN: another window or the lock screen owns the foreground'
    if ([WisprailCapture]::GetForegroundWindow() -eq $process.MainWindowHandle) {
        $rect = New-Object WisprailCapture+RECT
        if (-not [WisprailCapture]::GetWindowRect($process.MainWindowHandle, [ref]$rect)) { throw 'Cannot read window bounds.' }
        $image = New-Object Drawing.Bitmap(($rect.Right - $rect.Left), ($rect.Bottom - $rect.Top))
        $graphics = [Drawing.Graphics]::FromImage($image)
        try {
            $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $image.Size)
            $image.Save((Join-Path $output 'window.png'))
            $screenshot = 'CAPTURED'
        } finally { $graphics.Dispose(); $image.Dispose() }
    }
    # Cache the process handle before shutdown so ExitCode remains available after the OS removes it.
    $process.Handle | Out-Null
    if (-not $process.CloseMainWindow() -or -not $process.WaitForExit(15000)) { throw 'Normal UI shutdown did not finish.' }
    if ($process.ExitCode -ne 0) { throw "UI exited with code $($process.ExitCode)." }
    Start-Sleep -Milliseconds 500
    try {
        $events = @(Get-WinEvent -FilterHashtable @{LogName='Microsoft-Windows-CodeIntegrity/Operational'; StartTime=$start} -ErrorAction Stop)
    } catch {
        if ($_.FullyQualifiedErrorId -notlike 'NoMatchingEventsFound*') { throw }
        $events = @()
    }
    $blocked = @($events | Where-Object { $_.Id -in 3033,3077 -and $_.Message -like "*$app*" })
    $blocked | Select-Object TimeCreated,Id,Message | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'code-integrity.json') -Encoding UTF8
    if ($blocked.Count -ne 0) { throw 'Code Integrity blocked a module during the extracted ZIP launch.' }
    [ordered]@{
        status='PASS'; archive=[IO.Path]::GetFileName($archivePath); sha256=(Get-FileHash -LiteralPath $archivePath).Hash;
        version=$info.version; sourceSnapshot=$info.sourceSnapshot; started=$start.ToString('o'); finished=(Get-Date).ToString('o');
        elevated=$false; bundledJava=$true; runtimeSignedBinaries=$runtimeBinaries.Count; codeIntegrityBlocks=$blocked.Count;
        observedNativeModules=@('fontmanager.dll','glass.dll','javafx_font.dll','JNA'); exitCode=$process.ExitCode;
        windowObserved=$true; screenshot=$screenshot;
        serviceInstallation='NOT_RUN'; networkAcceptance='NOT_RUN'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding UTF8
    Copy-Item -LiteralPath (Join-Path $output 'result.json') -Destination (Join-Path $PSScriptRoot 'target\acceptance\latest-windows.json') -Force
    Write-Output $output
} finally {
    if ($null -ne $process) { $process.Dispose() }
    $launcher.Dispose()
}
