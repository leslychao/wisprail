[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Python = 'python',
    [switch]$ZipOnly,
    [switch]$WindowsOnly
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\jpackage.exe'))) {
    throw 'Передайте -JavaHome с Windows JDK 21.0.11 AMD64.'
}
$release = Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw
if ($release -notmatch 'JAVA_VERSION="21\.0\.11"' -or $release -notmatch 'OS_ARCH="(x86_64|amd64)"') {
    throw 'Упаковке Windows AMD64 требуется закреплённый JDK 21.0.11 AMD64.'
}
$env:JAVA_HOME = $JavaHome
if (-not $WindowsOnly) {
    $docker = (Get-Command docker.exe -ErrorAction Stop).Source
}
$target = Join-Path $PSScriptRoot 'target'
$work = Join-Path $target ('windows-' + [guid]::NewGuid().ToString('N'))
$source = Join-Path $work 'source'
$packageInput = Join-Path $work 'input'
$dist = Join-Path $target 'dist'
New-Item -ItemType Directory -Force -Path $work,$packageInput,$dist | Out-Null
if (-not $ZipOnly) {
    & $Python (Join-Path $PSScriptRoot 'fetch-tools.py') --target windows
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось получить проверенный WiX 3.14.1.' }
    $wix = Join-Path $work 'wix'
    Expand-Archive -LiteralPath (Join-Path $target 'tools\wix314-binaries.zip') -DestinationPath $wix
    $env:PATH = $wix + [IO.Path]::PathSeparator + $env:PATH
}
& $Python (Join-Path $PSScriptRoot 'source-snapshot.py') --output $source
if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать точный снимок исходников.' }
Set-Location -LiteralPath $source
$sourcePackaging = Join-Path $source 'packaging'
$cachedArchive = Join-Path $target 'engine\windows-amd64\sing-box-1.14.2-windows-amd64.zip'
if (Test-Path -LiteralPath $cachedArchive) {
    $engineCache = Join-Path $sourcePackaging 'target\engine\windows-amd64'
    New-Item -ItemType Directory -Force -Path $engineCache | Out-Null
    Copy-Item -LiteralPath $cachedArchive -Destination $engineCache
}
& $Python (Join-Path $sourcePackaging 'fetch-engine.py') --platform windows-amd64
if ($LASTEXITCODE -ne 0) { throw 'Не удалось получить проверенный движок.' }
& (Join-Path $source 'mvnw.cmd') -B -ntp clean install
if ($LASTEXITCODE -ne 0) { throw 'Maven verify/install из снимка завершился ошибкой.' }
[xml]$pom = Get-Content -LiteralPath (Join-Path $source 'pom.xml')
$version = $pom.project.version
& (Join-Path $source 'mvnw.cmd') -B -ntp -pl desktop dependency:copy-dependencies '-DincludeScope=runtime' ('-DoutputDirectory=' + $packageInput)
if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать runtime зависимости.' }
Copy-Item -LiteralPath (Join-Path $source "desktop\target\wisprail-desktop-$version.jar") -Destination $packageInput
$engine = Join-Path $packageInput 'engine'
$platform = Join-Path $packageInput 'platform\windows'
New-Item -ItemType Directory -Force -Path $engine,$platform | Out-Null
$engineSource = Join-Path $sourcePackaging 'target\engine\windows-amd64'
Copy-Item -LiteralPath (Join-Path $engineSource 'sing-box.exe'),(Join-Path $engineSource 'sing-box.sha256'),(Join-Path $engineSource 'LICENSE') -Destination $engine
Copy-Item -Path (Join-Path $sourcePackaging 'windows\*-service.ps1') -Destination $platform
$agentProperties = Join-Path $work 'agent.properties'
@"
main-jar=wisprail-desktop-$version.jar
main-class=app.wisprail.agent.AgentMain
win-console=true
win-shortcut=false
win-menu=false
"@ | Set-Content -LiteralPath $agentProperties -Encoding utf8
$jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
& $jpackage --type app-image --name Wisprail --app-version $version --vendor Wisprail --input $packageInput --main-jar "wisprail-desktop-$version.jar" --main-class app.wisprail.ui.Launcher --add-launcher ('WisprailAgent=' + $agentProperties) --add-modules 'java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.security.jgss,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.naming.dns,jdk.security.auth,jdk.net' --dest $work
if ($LASTEXITCODE -ne 0) { throw 'jpackage app-image завершился ошибкой.' }
$image = Join-Path $work 'Wisprail'
# jlink extracts unsigned PE files from jmods. Preserve the signed vendor binaries
# from the exact same JDK for native files already selected into this runtime.
$runtime = Join-Path $image 'runtime'
$vendorNativeFiles = 0
foreach ($native in Get-ChildItem -LiteralPath $runtime -Recurse -File | Where-Object { $_.Extension -in '.dll', '.exe' }) {
    $relative = $native.FullName.Substring($runtime.Length + 1)
    $vendorFile = Join-Path $JavaHome $relative
    if (-not (Test-Path -LiteralPath $vendorFile -PathType Leaf)) {
        throw "В закреплённом JDK отсутствует runtime native: $relative"
    }
    Copy-Item -LiteralPath $vendorFile -Destination $native.FullName -Force
    if ((Get-FileHash -LiteralPath $vendorFile).Hash -ne (Get-FileHash -LiteralPath $native.FullName).Hash) {
        throw "Копия runtime native отличается от поставщика: $relative"
    }
    $vendorSignature = Get-AuthenticodeSignature -LiteralPath $vendorFile
    $runtimeSignature = Get-AuthenticodeSignature -LiteralPath $native.FullName
    if ($runtimeSignature.Status -ne $vendorSignature.Status) {
        throw "Подпись runtime native отличается от поставщика: $relative"
    }
    $vendorNativeFiles++
}
Write-Output "Preserved $vendorNativeFiles original JDK native files and their Authenticode status."
$zip = Join-Path $dist "Wisprail-$version-windows-amd64.zip"
if (Test-Path -LiteralPath $zip) { throw "Артефакт уже существует: $zip" }
Compress-Archive -LiteralPath $image -DestinationPath $zip
if (-not $ZipOnly) {
    & $jpackage --type exe --app-image $image --name Wisprail --app-version $version --vendor Wisprail --win-menu --win-shortcut --win-dir-chooser --win-upgrade-uuid '6b517521-f1d0-45e8-95d8-540fa8bab8a8' --resource-dir (Join-Path $sourcePackaging 'windows') --dest $dist
    if ($LASTEXITCODE -ne 0) { throw 'Создание EXE завершилось ошибкой. Для JDK21 нужны WiX candle.exe и light.exe в PATH.' }
}
Copy-Item -LiteralPath (Join-Path $work 'sources.zip') -Destination (Join-Path $dist "Wisprail-$version-sources.zip")
Copy-Item -LiteralPath (Join-Path $source 'source-manifest.json') -Destination (Join-Path $dist "Wisprail-$version-source-manifest.json")
$manifest = [ordered]@{
    application = $version
    target = 'windows-amd64'
    jdk = '21.0.11'
    engine = '1.14.2'
    engineArchiveSha256 = 'c2d8bfff918755808781dfdeeb8581b6c91eb3a243d9a7b55483cfc0c0684d32'
    sourceArchiveSha256 = (Get-FileHash -LiteralPath (Join-Path $work 'sources.zip') -Algorithm SHA256).Hash.ToLowerInvariant()
    builtAtUtc = [DateTime]::UtcNow.ToString('o')
    signing = 'unsigned'
    wix = '3.14.1'
    preservedVendorNativeFiles = $vendorNativeFiles
    verification = 'Maven verify; pinned engine check; loopback API integration; no system VPN acceptance'
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dist "Wisprail-$version-windows-amd64-build.json") -Encoding utf8
$checksums = Get-ChildItem -LiteralPath $dist -File | Where-Object {
    $_.Name -like "Wisprail-$version-windows-amd64*" -or
    $_.Name -eq "Wisprail-$version.exe" -or
    $_.Name -eq "Wisprail-$version-sources.zip" -or
    $_.Name -eq "Wisprail-$version-source-manifest.json"
} | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
    "$($hash.Hash.ToLowerInvariant())  $($_.Name)"
}
$checksums | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS-windows-amd64') -Encoding ascii
Set-Location -LiteralPath $workspace
$checksums
if (-not $WindowsOnly) {
    & $Python (Join-Path $PSScriptRoot 'fetch-tools.py') --target macos
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось получить проверенные инструменты macOS.' }
    & $docker build --tag wisprail-macos-cross:21.0.11 --file (Join-Path $sourcePackaging 'macos\cross\Dockerfile') (Join-Path $target 'tools')
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать образ кросс-сборки macOS.' }
    foreach ($architecture in @('arm64', 'amd64')) {
        & $docker run --rm --mount "type=bind,source=$workspace,target=/workspace" wisprail-macos-cross:21.0.11 $architecture "/opt/jdk-mac-$architecture/Contents/Home"
        if ($LASTEXITCODE -ne 0) { throw "Не удалось собрать дистрибутив macOS $architecture." }
    }
}
Get-ChildItem -LiteralPath $dist -File | Where-Object { $_.Name -like "Wisprail-$version*" } | Sort-Object Name | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
    "$($hash.Hash.ToLowerInvariant())  $($_.Name)"
} | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS') -Encoding ascii
