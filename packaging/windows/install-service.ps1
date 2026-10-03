param([Parameter(Mandatory = $true)][ValidatePattern('^S-1-[0-9-]+$')][string]$AllowedSid)
$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this installer through the Windows UAC prompt.'
}
$applicationDirectory = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$installDirectory = Split-Path -Parent $applicationDirectory
if ($installDirectory -ine (Join-Path $env:ProgramFiles 'Wisprail')) {
    throw 'Install the application in Program Files\Wisprail before enabling its privileged service.'
}
$installationItems = @(Get-Item -LiteralPath $installDirectory) + @(Get-ChildItem -LiteralPath $installDirectory -Recurse -Force)
if ($installationItems | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
    throw 'Reparse points in installation are forbidden.'
}
$launcher = Join-Path $installDirectory 'WisprailAgent.exe'
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) { throw 'Service launcher is missing.' }
$existing = Get-Service -Name Wisprail -ErrorAction SilentlyContinue
if ($null -ne $existing) {
    $serviceConfiguration = Get-CimInstance -ClassName Win32_Service -Filter "Name='Wisprail'"
    if ($serviceConfiguration.PathName.Trim('"') -ine $launcher) { throw 'The existing service is not owned by this installation.' }
    $savedIdentity = Get-Content -LiteralPath (Join-Path $applicationDirectory 'agent.json') -Raw | ConvertFrom-Json
    if ($savedIdentity.allowedUser -ne $AllowedSid) { throw 'The existing installation belongs to another user.' }
    Stop-Service -Name Wisprail
    $existing.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(75))
}
# Every executable, library, configuration and parent in the installed tree must be immutable to users.
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$acl.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
foreach ($entry in @(@('S-1-5-18', 'FullControl'), @('S-1-5-32-544', 'FullControl'), @('S-1-5-32-545', 'ReadAndExecute'))) {
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        [Security.Principal.SecurityIdentifier]::new($entry[0]), $entry[1], 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $installDirectory -AclObject $acl
Get-ChildItem -LiteralPath $installDirectory -Recurse -Force | ForEach-Object {
    if ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse points in installation are forbidden.' }
    $childAcl = Get-Acl -LiteralPath $_.FullName
    $childAcl.SetAccessRuleProtection($false, $false)
    foreach ($rule in @($childAcl.Access | Where-Object { -not $_.IsInherited })) { $childAcl.RemoveAccessRuleSpecific($rule) }
    $childAcl.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
    Set-Acl -LiteralPath $_.FullName -AclObject $childAcl
}
$dataDirectory = Join-Path $env:ProgramData 'Wisprail'
if (Test-Path -LiteralPath $dataDirectory) {
    if ((Get-Item -LiteralPath $dataDirectory).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'The data directory must not be a reparse point.' }
    $dataOwner = (Get-Acl -LiteralPath $dataDirectory).GetOwner([Security.Principal.SecurityIdentifier]).Value
    if ($dataOwner -notin 'S-1-5-18', 'S-1-5-32-544') { throw 'An untrusted account owns the service data directory.' }
    Get-ChildItem -LiteralPath $dataDirectory -Recurse -Force | ForEach-Object {
        if ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse points in service state are forbidden.' }
        $owner = (Get-Acl -LiteralPath $_.FullName).GetOwner([Security.Principal.SecurityIdentifier]).Value
        if ($owner -notin 'S-1-5-18', 'S-1-5-32-544') { throw 'An untrusted account owns a service state object.' }
    }
} else {
    New-Item -ItemType Directory -Path $dataDirectory | Out-Null
}
$dataAcl = [Security.AccessControl.DirectorySecurity]::new()
$dataAcl.SetAccessRuleProtection($true, $false)
$dataAcl.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
foreach ($sid in @('S-1-5-18', 'S-1-5-32-544')) {
    $dataAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
}
Set-Acl -LiteralPath $dataDirectory -AclObject $dataAcl
@{ allowedUser = $AllowedSid } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $applicationDirectory 'agent.json') -Encoding UTF8
$binaryPath = '"' + $launcher + '"'
if ($null -eq $existing) {
    New-Service -Name Wisprail -DisplayName 'Wisprail VPN control' -BinaryPathName $binaryPath -StartupType Automatic | Out-Null
} else {
    & "$env:SystemRoot\System32\sc.exe" config Wisprail binPath= $binaryPath start= auto
    if ($LASTEXITCODE -ne 0) { throw 'Service update failed.' }
}
& "$env:SystemRoot\System32\sc.exe" failure Wisprail reset= 86400 actions= restart/5000/restart/15000
if ($LASTEXITCODE -ne 0) { throw 'Service recovery configuration failed.' }
Start-Service -Name Wisprail
(Get-Service -Name Wisprail).WaitForStatus('Running', [TimeSpan]::FromSeconds(75))
