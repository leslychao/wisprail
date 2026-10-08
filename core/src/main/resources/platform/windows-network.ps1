param([Parameter(Mandatory = $true)][ValidateSet('Inspect', 'ApplyDns', 'RemoveDns')][string]$Operation)

$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$request = [Console]::In.ReadToEnd() | ConvertFrom-Json

function Get-Namespace([string]$value) {
    return $value.TrimStart('.').TrimEnd('.').ToLowerInvariant()
}

function Get-OwnedRules {
    return @(Get-DnsClientNrptRule | Where-Object { $_.Comment -eq $request.marker })
}

if ($Operation -eq 'Inspect') {
    $routes = @(Get-NetRoute -AddressFamily IPv4 | Select-Object DestinationPrefix, NextHop, InterfaceAlias, InterfaceIndex)
    $interfaces = @(Get-NetIPInterface -AddressFamily IPv4 | Select-Object InterfaceAlias, InterfaceIndex)
    $rules = @(Get-DnsClientNrptRule | Select-Object Name, Namespace, NameServers, Comment)
    $effective = @(Get-DnsClientNrptPolicy -Effective | Select-Object Namespace, NameServers)
    @{ routes = $routes; interfaces = $interfaces; rules = $rules; effective = $effective } | ConvertTo-Json -Depth 8 -Compress
    exit 0
}

if ($request.marker -notmatch '^Wisprail:[0-9a-f-]{36}:[0-9a-f-]{36}$') {
    throw 'Invalid ownership marker'
}
$address = [System.Net.IPAddress]::Parse([string]$request.server)
if ($address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw 'IPv4 DNS listener required'
}
$domains = @($request.domains)
if ($domains.Count -lt 1 -or $domains.Count -gt 4096) {
    throw 'Invalid domain count'
}
$namespaces = [System.Collections.Generic.List[string]]::new()
foreach ($domain in $domains) {
    if ($domain.Length -gt 253 -or $domain -notmatch '^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$') {
        throw 'Invalid DNS suffix'
    }
    $namespaces.Add($domain)
    $namespaces.Add(".${domain}")
}

if ($Operation -eq 'ApplyDns') {
    if ((Get-OwnedRules).Count -ne 0) {
        throw 'Operation already owns DNS objects'
    }
    $existingRules = @(Get-DnsClientNrptRule)
    $effectiveRules = @(Get-DnsClientNrptPolicy -Effective)
    foreach ($rule in @($existingRules) + @($effectiveRules)) {
        foreach ($existing in @($rule.Namespace)) {
            $normalized = Get-Namespace $existing
            foreach ($domain in $domains) {
                if ($normalized.Length -eq 0 -or $domain -eq $normalized -or
                    $domain.EndsWith(".${normalized}") -or $normalized.EndsWith(".${domain}")) {
                    throw 'An existing DNS policy overlaps this profile'
                }
            }
        }
    }
    $created = Add-DnsClientNrptRule -Namespace $namespaces -NameServers $address.ToString() `
        -Comment $request.marker -DisplayName 'Wisprail' -PassThru
    @{ ids = @($created.Name) } | ConvertTo-Json -Compress
    exit 0
}

$ids = @($request.ids)
if ($ids.Count -gt 4096) { throw 'Invalid rule identifier count' }
$allRules = @(Get-DnsClientNrptRule)
foreach ($rule in Get-OwnedRules) {
    if ($rule.Name -notin $ids) { throw 'Unrecorded DNS rule; cleanup refused' }
}
foreach ($id in $ids) {
    $rule = $allRules | Where-Object { $_.Name -eq $id }
    if ($null -eq $rule) { continue }
    if ($rule.Comment -ne $request.marker) { throw 'DNS rule owner changed; cleanup refused' }
    $actualNamespaces = @($rule.Namespace | Sort-Object)
    $expectedNamespaces = @($namespaces | Sort-Object)
    $sameNamespaces = ($actualNamespaces -join "`n") -ceq ($expectedNamespaces -join "`n")
    if (!$sameNamespaces -or [string]$rule.NameServers -ne $address.ToString()) {
        throw 'An owned DNS rule was changed externally; automatic removal refused'
    }
    Remove-DnsClientNrptRule -Name $rule.Name -Force
}
if ((Get-OwnedRules).Count -ne 0) {
    throw 'DNS cleanup was not confirmed'
}
'{}'
