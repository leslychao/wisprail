$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$request = [Console]::In.ReadToEnd() | ConvertFrom-Json
$ownershipTag = ''
if ($request.mode -ne 'inspect') {
    $sessionId = [Guid]::Parse($request.session)
    $ownershipTag = 'Wisprail/' + $sessionId.ToString()
}

switch ($request.mode) {
    'inspect' {
        $routes = @(Get-NetRoute -AddressFamily IPv4 | ForEach-Object {
            @{ prefix = $_.DestinationPrefix; interfaceName = $_.InterfaceAlias }
        })
        $localRules = @(Get-DnsClientNrptRule)
        $dnsRules = @(Get-DnsClientNrptPolicy -Effective | ForEach-Object {
            $policy = $_
            foreach ($namespace in $policy.Namespace) {
                $owners = @($localRules | Where-Object {
                    $_.Namespace -contains $namespace -and
                    [string]$_.NameServers -eq [string]$policy.NameServers -and
                    $_.Comment -cmatch '^Wisprail/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'
                })
                $owner = ''
                if ($owners.Count -eq 1) { $owner = $owners[0].Comment.Substring(9) }
                @{ domain = [string]$namespace; owner = $owner }
            }
        })
        @{ routes = $routes; dnsRules = $dnsRules } | ConvertTo-Json -Depth 5 -Compress
    }
    'install' {
        $dnsAddress = [System.Net.IPAddress]::Parse($request.address)
        if ($dnsAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
            throw 'IPv4 DNS required'
        }
        foreach ($domain in $request.domains) {
            if ($domain -notmatch '^[a-z0-9][a-z0-9.-]{0,252}$') { throw 'Invalid domain' }
            Add-DnsClientNrptRule -Namespace @($domain, ".$domain") -NameServers $dnsAddress.ToString() `
                -DisplayName 'Wisprail' -Comment $ownershipTag | Out-Null
        }
        $effectiveRules = @(Get-DnsClientNrptPolicy -Effective)
        foreach ($domain in $request.domains) {
            foreach ($namespace in @($domain, ".$domain")) {
                $matching = @($effectiveRules | Where-Object { $_.Namespace -contains $namespace })
                if ($matching.Count -ne 1 -or [string]$matching[0].NameServers -ne $dnsAddress.ToString()) {
                    throw 'Owned DNS rule is not effective; another policy may override it'
                }
            }
        }
        @{ ok = $true } | ConvertTo-Json -Compress
    }
    'remove' {
        $ownedRules = @(Get-DnsClientNrptRule | Where-Object { $_.Comment -ceq $ownershipTag })
        foreach ($rule in $ownedRules) {
            Remove-DnsClientNrptRule -Name $rule.Name -Force
        }
        @{ ok = $true } | ConvertTo-Json -Compress
    }
    'exists' {
        $ownedRules = @(Get-DnsClientNrptRule | Where-Object { $_.Comment -ceq $ownershipTag })
        @{ count = $ownedRules.Count } | ConvertTo-Json -Compress
    }
    default { throw 'Unsupported operation' }
}
