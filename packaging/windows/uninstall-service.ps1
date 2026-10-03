$ErrorActionPreference = 'Stop'
$service = Get-Service -Name Wisprail -ErrorAction SilentlyContinue
if ($null -ne $service) {
    $expectedLauncher = Join-Path (Split-Path -Parent $PSScriptRoot) 'WisprailAgent.exe'
    $installed = Get-CimInstance Win32_Service -Filter "Name='Wisprail'"
    if ($installed.PathName.Trim('"') -ne $expectedLauncher) {
        throw 'The service belongs to another installation. It was not modified.'
    }
    Stop-Service -Name Wisprail
    $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(75))
    $ownership = Join-Path $env:ProgramData 'Wisprail\service\runtime\ownership.json'
    if (Test-Path -LiteralPath $ownership) {
        throw 'Network cleanup is not confirmed. Start Wisprail and retry cleanup before uninstalling.'
    }
    & "$env:SystemRoot\System32\sc.exe" delete Wisprail
    if ($LASTEXITCODE -ne 0) { throw 'Service removal failed.' }
}
# Profiles and protected secrets intentionally survive uninstall; no unrelated DNS rules are removed.
