[CmdletBinding()]
param([Parameter(Mandatory)][string]$InstallRoot, [switch]$RecordRollback)
$ErrorActionPreference = 'Stop'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Удаление сетевого компонента требует прав администратора.'
}
$root = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
$binaryPath = '"' + (Join-Path $root 'WisprailAgent.exe') + '" --service'
$existing = Get-CimInstance Win32_Service -Filter "Name='WisprailAgent'"
if ($RecordRollback) {
    & (Join-Path $PSScriptRoot 'install-service.ps1') -InstallRoot $root -Operation PrepareRemove
}
if ($existing) {
    if ($existing.PathName -ne $binaryPath -or $existing.StartName -ne 'LocalSystem') {
        throw 'Существующая служба не принадлежит этой установке Wisprail.'
    }
    if ($existing.State -eq 'Stopped') {
        # Startup recovers interrupted work before the final stop verifies cleanup.
        Start-Service -Name WisprailAgent
        (Get-Service -Name WisprailAgent).WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
    }
    Stop-Service -Name WisprailAgent
    (Get-Service -Name WisprailAgent).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
    $stopped = Get-CimInstance Win32_Service -Filter "Name='WisprailAgent'"
    if ($stopped.ExitCode -ne 0) {
        throw 'Очистка сети не подтверждена. Исправьте компонент перед удалением приложения.'
    }
    & sc.exe delete WisprailAgent | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось удалить службу Windows.' }
}
# Profiles, secrets and the established user identity survive uninstall.
