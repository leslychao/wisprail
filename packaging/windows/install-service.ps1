[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InstallRoot,
    [string]$OwnerSid,
    [ValidateSet('Install', 'Rollback', 'Commit', 'PrepareRemove', 'CommitRemove', 'Restore')][string]$Operation = 'Install',
    [string]$InstallerTransaction
)
$ErrorActionPreference = 'Stop'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Установка сетевого компонента требует прав администратора.'
}
$root = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
$programFiles = [IO.Path]::GetFullPath($env:ProgramFiles).TrimEnd('\')
if (-not $root.StartsWith($programFiles + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Служба устанавливается только из Program Files.'
}
$launcher = Join-Path $root 'WisprailAgent.exe'
$stateParent = Join-Path $env:ProgramData 'Wisprail'
$state = Join-Path $stateParent 'agent'
$transactionFile = Join-Path $stateParent 'installer-transaction.json'
$removeJournal = Join-Path $stateParent 'uninstall-transaction.json'
$ownerFile = Join-Path $state 'owner.txt'
$binaryPath = '"' + $launcher + '" --service'
$systemSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$adminsSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$trustedSids = @($systemSid.Value, $adminsSid.Value,
    'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464')
$unsafeRights = [Security.AccessControl.FileSystemRights]::Write -bor
    [Security.AccessControl.FileSystemRights]::Delete -bor
    [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
    [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
    [Security.AccessControl.FileSystemRights]::TakeOwnership
if ($InstallerTransaction -and $InstallerTransaction -notmatch '^\{[0-9A-Fa-f-]{36}\}$') {
    throw 'Недопустимый идентификатор транзакции установщика.'
}

function Assert-NoReparse([string]$Path) {
    $candidate = [IO.Path]::GetFullPath($Path)
    while ($candidate) {
        if (Test-Path -LiteralPath $candidate) {
            $item = Get-Item -LiteralPath $candidate -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Системные пути не должны проходить через ссылки или junctions.'
            }
        }
        $candidate = Split-Path -Parent $candidate
    }
}

function Assert-TrustedObject([string]$Path) {
    Assert-NoReparse $Path
    $acl = Get-Acl -LiteralPath $Path
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $trustedSids) {
        throw 'Системный объект создан посторонним пользователем.'
    }
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.IdentityReference.Value -eq 'S-1-3-0' -and
            ($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0) {
            continue
        }
        if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
            ($rule.FileSystemRights -band $unsafeRights) -ne 0 -and
            $rule.IdentityReference.Value -notin $trustedSids) {
            throw 'Системный объект доступен для посторонней записи.'
        }
    }
}

function Set-SystemAcl([string]$Path, [bool]$Directory) {
    Assert-NoReparse $Path
    if ($Directory) {
        $acl = [Security.AccessControl.DirectorySecurity]::new()
        $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    } else {
        $acl = [Security.AccessControl.FileSecurity]::new()
        $inheritance = [Security.AccessControl.InheritanceFlags]::None
    }
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($identity in @($systemSid, $adminsSid)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity,
            [Security.AccessControl.FileSystemRights]::FullControl, $inheritance,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    # Change exactly this object; never walk an untrusted descendant tree.
    & icacls.exe $Path /setowner '*S-1-5-18' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось назначить системного владельца.' }
}

function Write-SystemFile([string]$Path, [string]$Value) {
    Assert-NoReparse $Path
    $temporary = Join-Path (Split-Path -Parent $Path) ([Guid]::NewGuid().ToString() + '.tmp')
    try {
        $stream = [IO.FileStream]::new($temporary, [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            $encoding = [Text.UTF8Encoding]::new([IO.Path]::GetFileName($Path) -ne 'owner.txt')
            $bytes = $encoding.GetBytes($Value)
            $preamble = $encoding.GetPreamble()
            $stream.Write($preamble, 0, $preamble.Length)
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
        } finally {
            $stream.Dispose()
        }
        Set-SystemAcl $temporary $false
        if (Test-Path -LiteralPath $Path) {
            Assert-TrustedObject $Path
            [IO.File]::Replace($temporary, $Path, $null)
        } else {
            [IO.File]::Move($temporary, $Path)
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}

function Get-OwnedService {
    $service = Get-CimInstance Win32_Service -Filter "Name='WisprailAgent'"
    if ($service -and ($service.PathName -ne $binaryPath -or $service.StartName -ne 'LocalSystem')) {
        throw 'Существующая служба не принадлежит этой установке Wisprail.'
    }
    return $service
}

# Establish trust before reading retained identity, journal, or changing ACLs.
$ancestor = $root
while ($ancestor.StartsWith($programFiles, [StringComparison]::OrdinalIgnoreCase)) {
    if ($Operation -ne 'CommitRemove' -or (Test-Path -LiteralPath $ancestor)) {
        Assert-TrustedObject $ancestor
    }
    $ancestor = Split-Path -Parent $ancestor
}
foreach ($directory in @($stateParent, $state)) {
    Assert-NoReparse $directory
    if (-not (Test-Path -LiteralPath $directory)) {
        [IO.Directory]::CreateDirectory($directory) | Out-Null
    }
    Assert-TrustedObject $directory
    Set-SystemAcl $directory $true
}
foreach ($file in @($ownerFile, $transactionFile, $removeJournal)) {
    if (Test-Path -LiteralPath $file) { Assert-TrustedObject $file }
}

if ($Operation -eq 'PrepareRemove') {
    if (Test-Path -LiteralPath $removeJournal) { throw 'Предыдущее удаление не завершено.' }
    $service = Get-OwnedService
    $record = @{ root = $root; binaryPath = $binaryPath; existed = ($null -ne $service) } |
        ConvertTo-Json -Compress
    Write-SystemFile $removeJournal $record
    exit 0
}
if ($Operation -eq 'Restore' -or $Operation -eq 'CommitRemove') {
    if (-not (Test-Path -LiteralPath $removeJournal)) { exit 0 }
    if ((Get-Item -LiteralPath $removeJournal).Length -gt 4096) { throw 'Повреждён журнал удаления.' }
    $record = Get-Content -LiteralPath $removeJournal -Raw | ConvertFrom-Json
    if ($record.root -ne $root -or $record.binaryPath -ne $binaryPath -or $record.existed -isnot [bool]) {
        throw 'Журнал удаления не принадлежит этой установке.'
    }
    if ($Operation -eq 'CommitRemove' -or -not $record.existed) {
        Remove-Item -LiteralPath $removeJournal
        exit 0
    }
}

if ($Operation -eq 'Rollback' -or $Operation -eq 'Commit') {
    if (-not $InstallerTransaction -or -not (Test-Path -LiteralPath $transactionFile)) { exit 0 }
    if ((Get-Item -LiteralPath $transactionFile).Length -gt 4096) { throw 'Повреждён журнал установки.' }
    $transaction = Get-Content -LiteralPath $transactionFile -Raw | ConvertFrom-Json
    if ($transaction.id -ne $InstallerTransaction -or $transaction.root -ne $root -or
        $transaction.binaryPath -ne $binaryPath -or $transaction.createdService -isnot [bool]) {
        throw 'Транзакция не принадлежит этой установке.'
    }
    $service = Get-OwnedService
    if ($Operation -eq 'Rollback' -and $service) {
        if ($transaction.createdService) {
            & (Join-Path $PSScriptRoot 'remove-service.ps1') -InstallRoot $root
        } elseif ($service.State -ne 'Stopped') {
            Stop-Service -Name WisprailAgent
            (Get-Service -Name WisprailAgent).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
            $stopped = Get-OwnedService
            if ($stopped.ExitCode -ne 0) { throw 'Служба не подтвердила очистку сети.' }
        }
    }
    Remove-Item -LiteralPath $transactionFile
    exit 0
}

if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw 'Комплектный WisprailAgent.exe не найден.'
}
Assert-TrustedObject $launcher
$existing = Get-OwnedService
if (Test-Path -LiteralPath $transactionFile) { throw 'Предыдущая транзакция установки не завершена.' }
if (Test-Path -LiteralPath $ownerFile) {
    if ((Get-Item -LiteralPath $ownerFile).Length -gt 256) { throw 'Повреждён владелец службы.' }
    $OwnerSid = (Get-Content -LiteralPath $ownerFile -Raw).Trim()
}
if ($OwnerSid -notmatch '^S-1-(?:5-21-(?:\d+-){3}\d+|12-1-(?:\d+-){3}\d+)$') {
    throw 'Требуется SID обычного локального, доменного или Entra-пользователя Windows.'
}
$null = [Security.Principal.SecurityIdentifier]::new($OwnerSid)
if (-not (Test-Path -LiteralPath $ownerFile)) { Write-SystemFile $ownerFile $OwnerSid }
Set-SystemAcl $ownerFile $false
if ($InstallerTransaction) {
    $transaction = @{ id = $InstallerTransaction; root = $root; binaryPath = $binaryPath
        createdService = ($null -eq $existing) } | ConvertTo-Json -Compress
    Write-SystemFile $transactionFile $transaction
}
if ($Operation -eq 'Install') {
    # These exact packaged scripts survive RemoveFiles for MSI uninstall rollback.
    foreach ($name in @('install-service.ps1', 'remove-service.ps1')) {
        $source = Join-Path $PSScriptRoot $name
        Assert-TrustedObject $source
        if ((Get-Item -LiteralPath $source).Length -gt 262144) { throw 'Некорректный компонент установки.' }
        Write-SystemFile (Join-Path $stateParent $name) ([IO.File]::ReadAllText($source))
    }
}

$created = $false
try {
    if ($existing -and $existing.State -ne 'Stopped') {
        Stop-Service -Name WisprailAgent
        (Get-Service -Name WisprailAgent).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
        $stopped = Get-OwnedService
        if ($stopped.ExitCode -ne 0) { throw 'Служба не подтвердила очистку сети. Обновление остановлено.' }
    }
    if ($existing) {
        & sc.exe config WisprailAgent binPath= $binaryPath start= auto | Out-Null
    } else {
        & sc.exe create WisprailAgent binPath= $binaryPath start= auto obj= LocalSystem DisplayName= 'Wisprail Network Component' | Out-Null
        $created = $LASTEXITCODE -eq 0
    }
    if ($LASTEXITCODE -ne 0) { throw 'Регистрация службы Windows завершилась ошибкой.' }
    & sc.exe description WisprailAgent 'VPN и выборочный DNS Wisprail; соединение живёт только вместе с UI-сессией.' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось записать описание службы.' }
    Start-Service -Name WisprailAgent
    (Get-Service -Name WisprailAgent).WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
    if ($Operation -eq 'Restore') { Remove-Item -LiteralPath $removeJournal }
} catch {
    if ($created -and -not $InstallerTransaction) {
        & (Join-Path $PSScriptRoot 'remove-service.ps1') -InstallRoot $root
    }
    throw
}
