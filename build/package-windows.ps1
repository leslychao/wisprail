param(
    [ValidateSet('app-image', 'msi')][string]$Type = 'app-image',
    [string]$SigningThumbprint = '',
    [switch]$Release,
    [ValidateSet('x64')][string]$Architecture = 'x64',
    [string]$OutputDirectory = '',
    [string]$SourceSnapshot = 'working-tree'
)
$ErrorActionPreference = 'Stop'

function Assert-X64Binary([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $reader = New-Object IO.BinaryReader($stream)
    try {
        if ($reader.ReadUInt16() -ne 0x5a4d) { throw "Invalid PE file: $Path" }
        $stream.Position = 0x3c
        $offset = $reader.ReadInt32()
        if ($offset -lt 64 -or $offset -gt $stream.Length - 6) { throw "Invalid PE header: $Path" }
        $stream.Position = $offset
        if ($reader.ReadUInt32() -ne 0x4550 -or $reader.ReadUInt16() -ne 0x8664) {
            throw "Expected an x64 PE binary: $Path"
        }
    } finally { $reader.Dispose() }
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$repository = Split-Path -Parent $PSScriptRoot
$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
$jlink = Join-Path $env:JAVA_HOME 'bin\jlink.exe'
[xml]$pom = Get-Content -LiteralPath (Join-Path $repository 'pom.xml') -Raw
$version = $pom.project.version
$packageVersion = $version -replace '-.*$', ''
$jdkRelease = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release') -Raw
if ($jdkRelease -notmatch 'JAVA_VERSION="21\.0\.11"' -or $jdkRelease -notmatch 'OS_ARCH="(x86_64|amd64)"') {
    throw 'Windows packaging requires the accepted JDK 21.0.11 x64.'
}
if ($Release -and [string]::IsNullOrWhiteSpace($SigningThumbprint)) { throw 'Release requires a code-signing certificate thumbprint.' }
if ($Type -eq 'msi' -and $null -eq (Get-Command candle.exe -ErrorAction SilentlyContinue)) { throw 'MSI creation requires WiX 3 candle.exe and light.exe in PATH.' }
Push-Location $repository
try {
    $engine = Join-Path $PSScriptRoot 'target\engine\windows-x64'
    & python build\fetch-engine.py --platform windows-amd64 --output $engine
    if ($LASTEXITCODE -ne 0) { throw 'Engine verification failed.' }
    & .\mvnw.cmd -B -ntp "-Dwisprail.engine=$engine\sing-box.exe" clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
    if (-not (Test-Path -LiteralPath (Join-Path $engine 'sing-box.exe'))) { throw 'Run build/fetch-engine.py --platform windows-amd64 first.' }
    $output = $OutputDirectory
    if ([string]::IsNullOrWhiteSpace($output)) {
        $output = Join-Path $PSScriptRoot ('target\windows-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    }
    if (Test-Path -LiteralPath $output) { throw 'Packaging output must be a new directory.' }
    $inputDirectory = Join-Path $output 'input'
    New-Item -ItemType Directory -Path $inputDirectory -Force | Out-Null
    Copy-Item -LiteralPath "frontend\target\wisprail-desktop-$version.jar" -Destination $inputDirectory
    Get-ChildItem -LiteralPath 'frontend\target\lib' -Filter '*.jar' | Where-Object { $_.Name -notlike 'javafx-*' } | Copy-Item -Destination $inputDirectory
    Copy-Item -LiteralPath 'frontend\target\javafx' -Destination (Join-Path $inputDirectory 'javafx') -Recurse
    Copy-Item -LiteralPath $engine -Destination (Join-Path $inputDirectory 'engine') -Recurse
    Copy-Item -LiteralPath 'backend\src\main\resources\platform\windows-network.ps1' -Destination $inputDirectory
    Copy-Item -LiteralPath 'build\windows\install-service.ps1', 'build\windows\uninstall-service.ps1', 'build\THIRD_PARTY_NOTICES.md' -Destination $inputDirectory
    $launcherProperties = Join-Path $output 'agent-launcher.properties'
    @("main-jar=wisprail-backend-$version.jar", 'main-class=app.wisprail.agent.AgentMain', 'win-console=false') | Set-Content -LiteralPath $launcherProperties -Encoding ASCII
    $runtime = Join-Path $output 'runtime'
    & $jlink --add-modules java.base,java.desktop,java.logging,java.naming,java.net.http,java.security.jgss,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.net,jdk.management --strip-debug --no-header-files --no-man-pages --output $runtime
    if ($LASTEXITCODE -ne 0) { throw 'Runtime creation failed.' }
    # Microsoft signs the installed JDK binaries, but its JMOD payloads are unsigned.
    # Use the native binaries from this exact JDK; never re-sign them with an application key.
    $runtimePrefix = $runtime.TrimEnd('\') + '\'
    foreach ($binary in Get-ChildItem -LiteralPath $runtime -Recurse -File | Where-Object { $_.Extension -in '.dll', '.exe' }) {
        $relative = $binary.FullName.Substring($runtimePrefix.Length)
        $original = Join-Path $env:JAVA_HOME $relative
        if (-not (Test-Path -LiteralPath $original -PathType Leaf)) { throw "Missing JDK native binary: $relative" }
        Assert-X64Binary $original
        if ((Get-AuthenticodeSignature -LiteralPath $original).Status -ne 'Valid') { throw "JDK native signature is not valid: $relative" }
        Copy-Item -LiteralPath $original -Destination $binary.FullName -Force
        if ((Get-FileHash -LiteralPath $original).Hash -ne (Get-FileHash -LiteralPath $binary.FullName).Hash) {
            throw "Runtime native binary differs from the JDK: $relative"
        }
    }
    $images = Join-Path $output 'images'
    & $jpackage --type app-image --name Wisprail --app-version $packageVersion --vendor Wisprail --input $inputDirectory --dest $images --main-jar "wisprail-desktop-$version.jar" --main-class app.wisprail.ui.DesktopLauncher --runtime-image $runtime --add-launcher "WisprailAgent=$launcherProperties" --java-options '-Dfile.encoding=UTF-8' --java-options '--module-path=$APPDIR\javafx' --java-options '--add-modules=javafx.controls'
    if ($LASTEXITCODE -ne 0) { throw 'Application packaging failed.' }
    $image = Join-Path $images 'Wisprail'
    Get-ChildItem -LiteralPath $image -Recurse -File | Where-Object { $_.Extension -in '.exe', '.dll' } | ForEach-Object {
        Assert-X64Binary $_.FullName
    }
    if (-not [string]::IsNullOrWhiteSpace($SigningThumbprint)) {
        $signTool = (Get-Command signtool.exe -ErrorAction Stop).Source
        Get-ChildItem -LiteralPath $image -Recurse -File | Where-Object { $_.Extension -in '.exe', '.dll' -and (Get-AuthenticodeSignature -LiteralPath $_.FullName).Status -ne 'Valid' } | ForEach-Object {
            & $signTool sign /sha1 $SigningThumbprint /fd SHA256 /tr 'http://timestamp.digicert.com' /td SHA256 $_.FullName
            if ($LASTEXITCODE -ne 0) { throw 'Code signing failed.' }
        }
        $certificate = Get-Item -LiteralPath ("Cert:\CurrentUser\My\" + $SigningThumbprint)
        Get-ChildItem -LiteralPath (Join-Path $image 'app') -Filter '*.ps1' | ForEach-Object {
            $signature = Set-AuthenticodeSignature -LiteralPath $_.FullName -Certificate $certificate -HashAlgorithm SHA256 -TimestampServer 'http://timestamp.digicert.com'
            if ($signature.Status -ne 'Valid') { throw 'PowerShell component signing failed.' }
        }
        # Signing changes the engine bytes; pin the signed payload used by the service.
        (Get-FileHash -LiteralPath (Join-Path $image 'app\engine\sing-box.exe') -Algorithm SHA256).Hash.ToLowerInvariant() | Set-Content -LiteralPath (Join-Path $image 'app\engine\sing-box.sha256') -Encoding ASCII
    }
    if ($Type -eq 'msi') {
        $resources = Join-Path $output 'installer-resources'
        & python build\prepare-windows-installer.py --jdk $env:JAVA_HOME --output $resources
        if ($LASTEXITCODE -ne 0) { throw 'Installer service lifecycle configuration failed.' }
        & $jpackage --type msi --app-image $image --name Wisprail --app-version $packageVersion --vendor Wisprail --dest $output --install-dir Wisprail --resource-dir $resources --win-menu --win-shortcut --win-upgrade-uuid '5f37c318-b252-4acf-b5f1-559708172238'
        if ($LASTEXITCODE -ne 0) { throw 'MSI creation failed.' }
        if (-not [string]::IsNullOrWhiteSpace($SigningThumbprint)) {
            Get-ChildItem -LiteralPath $output -Filter '*.msi' | ForEach-Object {
                & $signTool sign /sha1 $SigningThumbprint /fd SHA256 /tr 'http://timestamp.digicert.com' /td SHA256 $_.FullName
                if ($LASTEXITCODE -ne 0) { throw 'Installer signing failed.' }
            }
        }
    }
    $metadata = [ordered]@{ version = $version; platform = 'windows'; architecture = $Architecture; sourceSnapshot = $SourceSnapshot; engineVersion = '1.14.2'; applicationSigned = -not [string]::IsNullOrWhiteSpace($SigningThumbprint); runtimeNativeSignatures = 'vendor-preserved' }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $image 'build-info.json') -Encoding UTF8
    $zip = Join-Path $output "wisprail-$version-windows-x64.zip"
    Compress-Archive -LiteralPath $image -DestinationPath $zip -CompressionLevel Optimal
    Write-Output $zip
} finally {
    Pop-Location
}
