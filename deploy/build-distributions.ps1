param(
    [ValidateSet('docker', 'ssh')]
    [string]$MacBuildMode = 'docker',
    [string]$MacArm64Host = $env:WISPRAIL_MAC_ARM64_HOST,
    [string]$MacX64Host = $env:WISPRAIL_MAC_X64_HOST
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$powershell = Join-Path $PSHOME 'powershell.exe'
if ($PSVersionTable.PSEdition -eq 'Core') { $powershell = Join-Path $PSHOME 'pwsh.exe' }
$dist = Join-Path $PSScriptRoot 'target\dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$buildLock = [IO.File]::Open((Join-Path $dist '.build.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$buildId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$results = @()
$script:crossImage = $null

function Get-SourceFiles {
    $paths = & git -C $repository -c core.quotepath=false ls-files --cached --others --exclude-standard
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate the current working tree.' }
    return @($paths | Sort-Object -Unique | Where-Object {
        $_ -match '^(pom\.xml|mvnw(\.cmd)?|README\.md|ACCEPTANCE\.md|sing-box_javafx_vpn_solution\.md|vpn_javafx_design_spec\.md|vpn_javafx_prototype\.html|vpn_javafx_main\.png)$' -or
        $_ -match '^\.mvn/' -or $_ -eq '.run/Build Distributions.run.xml' -or $_ -match '^(backend|frontend)/(pom\.xml|src/)' -or
        ($_ -match '^deploy/' -and $_ -notmatch '/target/' -and ($_ -match '\.(ps1|sh|py|m|plist|md)$' -or $_ -match '/installer-scripts/(preinstall|postinstall)$')) -or
        $_ -match '^deploy/macos/cross/(Dockerfile|inputs\.json)$'
    })
}

function Invoke-MacCrossBuild([string]$Architecture, [string]$ArchiveName) {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'BLOCKED: Docker Desktop with a local Linux engine is required for Mac cross-builds.'
    }
    if (-not $script:crossImage) {
        $endpoint = $env:DOCKER_HOST
        if (-not $endpoint) {
            $endpoint = & docker context inspect --format '{{.Endpoints.docker.Host}}'
            if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: Cannot inspect the Docker context.' }
        }
        if ($endpoint -notmatch '^(npipe:////\./pipe/|unix:///)') {
            throw 'BLOCKED: Cross-builds require a local Docker endpoint; remote source upload is not enabled.'
        }
        $engine = & docker info --format '{{.OSType}}'
        if ($LASTEXITCODE -ne 0 -or $engine -ne 'linux') {
            throw 'BLOCKED: Docker Linux engine is unavailable. Start Docker Desktop in Linux containers mode.'
        }
        $context = Join-Path $source 'deploy\macos\cross'
        $inputHashes = @('Dockerfile', 'inputs.json', 'fetch-inputs.py', 'prepare-toolchain.py') |
            ForEach-Object { (Get-FileHash -LiteralPath (Join-Path $context $_)).Hash }
        $digest = [Security.Cryptography.SHA256]::Create()
        try {
            $toolHash = ([BitConverter]::ToString($digest.ComputeHash(
                [Text.Encoding]::UTF8.GetBytes(($inputHashes -join ''))))).Replace('-', '').ToLowerInvariant()
        } finally { $digest.Dispose() }
        $image = 'wisprail-macos-cross:tools-' + $toolHash.Substring(0, 16)
        $imageLog = Join-Path $work 'macos-toolchain.log'
        & docker build --progress=plain --platform linux/amd64 -t $image $context *> $imageLog
        if ($LASTEXITCODE -ne 0) { throw "Mac cross-toolchain build failed. See $imageLog" }
        $script:crossImage = $image
    }
    $output = Join-Path $work "macos-$Architecture"
    New-Item -ItemType Directory -Path $output | Out-Null
    $log = Join-Path $output 'build.log'
    Write-Host "Building macOS $Architecture in Docker. Log: $log"
    $arguments = @('run', '--rm', '--platform', 'linux/amd64',
        '--mount', "type=bind,source=$sourceArchive,target=/snapshot/source.tar,readonly",
        '--mount', "type=bind,source=$output,target=/result",
        '--mount', 'type=volume,source=wisprail-macos-maven,target=/maven',
        '--mount', "type=bind,source=$source/deploy/macos/cross/build.sh,target=/build.sh,readonly",
        $script:crossImage, 'sh', '/build.sh', $Architecture, $sourceHash)
    & docker @arguments *> $log
    if ($LASTEXITCODE -ne 0) { throw "macOS $Architecture cross-build failed. See $log" }
    Copy-Item -LiteralPath (Join-Path $output "package/$ArchiveName") -Destination (Join-Path $dist ($ArchiveName + '.partial'))
    Move-Item -LiteralPath (Join-Path $dist ($ArchiveName + '.partial')) -Destination (Join-Path $dist $ArchiveName)
}

function Invoke-MacBuild([string]$Architecture, [string]$SshHost, [string]$ArchiveName) {
    if ([string]::IsNullOrWhiteSpace($SshHost)) {
        throw "BLOCKED: set WISPRAIL_MAC_$($Architecture.ToUpperInvariant())_HOST to an authorized SSH alias."
    }
    if ($SshHost -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$') { throw 'Use an SSH configuration alias, not a shell command.' }
    $sshOptions = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-o', 'StrictHostKeyChecking=yes')
    $remote = (& ssh @sshOptions $SshHost 'mktemp -d /tmp/wisprail-build.XXXXXXXX').Trim()
    if ($LASTEXITCODE -ne 0 -or $remote -notmatch '^/tmp/wisprail-build\.[a-zA-Z0-9]+$') { throw 'Mac build directory could not be created.' }
    # Retain the isolated remote directory for diagnosis; never remove an arbitrary remote path.
    & scp @sshOptions $sourceArchive "${SshHost}:$remote/source.tar"
    if ($LASTEXITCODE -ne 0) { throw 'Source snapshot transfer failed.' }
    $macArchitecture = if ($Architecture -eq 'arm64') { 'arm64' } else { 'x86_64' }
    $command = 'cd ' + $remote + ' && test "$(shasum -a 256 source.tar | cut -d " " -f 1)" = ' + $sourceArchiveHash + ' && tar -xf source.tar && export JAVA_HOME=$(/usr/libexec/java_home -v 21.0.11 -a ' + $macArchitecture + ') && sh deploy/package-macos.sh --architecture ' + $Architecture + ' --source-snapshot ' + $sourceHash + ' --output ' + $remote + '/package'
    & ssh @sshOptions $SshHost $command
    if ($LASTEXITCODE -ne 0) { throw "macOS $Architecture packaging failed; logs and source remain in $remote." }
    & scp @sshOptions "${SshHost}:$remote/package/$ArchiveName" (Join-Path $dist ($ArchiveName + '.partial'))
    if ($LASTEXITCODE -ne 0) { throw 'Mac distribution transfer failed.' }
    $remoteHash = & ssh @sshOptions $SshHost "shasum -a 256 $remote/package/$ArchiveName"
    if ($LASTEXITCODE -ne 0 -or $remoteHash -notmatch '^([a-f0-9]{64}) ') { throw 'Cannot verify Mac archive transfer.' }
    if ((Get-FileHash -LiteralPath (Join-Path $dist ($ArchiveName + '.partial'))).Hash.ToLowerInvariant() -ne $Matches[1]) {
        throw 'Mac archive checksum changed during transfer.'
    }
    Move-Item -LiteralPath (Join-Path $dist ($ArchiveName + '.partial')) -Destination (Join-Path $dist $ArchiveName)
}

try {
    [ordered]@{ buildId = $buildId; status = 'PREPARING'; results = @() } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dist 'build-results.json') -Encoding UTF8
    $work = Join-Path $PSScriptRoot "target\builds\$buildId"
    $source = Join-Path $work 'source'
    New-Item -ItemType Directory -Path $source -Force | Out-Null
    $files = Get-SourceFiles
    $manifest = foreach ($relative in $files) {
        $original = Join-Path $repository $relative
        if (-not (Test-Path -LiteralPath $original -PathType Leaf)) { throw "Source changed during snapshot: $relative" }
        if ((Get-Item -LiteralPath $original).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Source links are not supported: $relative" }
        $hash = (Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash.ToLowerInvariant()
        $copy = Join-Path $source $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $copy) -Force | Out-Null
        Copy-Item -LiteralPath $original -Destination $copy
        if ((Get-FileHash -LiteralPath $copy).Hash.ToLowerInvariant() -ne $hash) { throw "Source changed during snapshot: $relative" }
        [ordered]@{ path = $relative; sha256 = $hash }
    }
    if (Compare-Object $files (Get-SourceFiles)) { throw 'Source file list changed during snapshot.' }
    foreach ($entry in $manifest) {
        if ((Get-FileHash -LiteralPath (Join-Path $repository $entry.path)).Hash.ToLowerInvariant() -ne $entry.sha256) {
            throw "Source changed during snapshot: $($entry.path)"
        }
    }
    [xml]$pom = Get-Content -LiteralPath (Join-Path $source 'pom.xml') -Raw
    $version = $pom.project.version
    if ($version -notmatch '^\d+\.\d+\.\d+(-[a-zA-Z0-9.-]+)?$') { throw 'Unsupported distribution version in pom.xml.' }
    $manifestText = ($manifest | ForEach-Object { $_.path + ' ' + $_.sha256 }) -join "`n"
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $sourceHash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($manifestText)))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    [ordered]@{ sha256 = $sourceHash; files = @($manifest) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $source 'source-manifest.json') -Encoding UTF8
    $sourceArchive = Join-Path $work 'source.tar'
    & tar -cf $sourceArchive -C $source .
    if ($LASTEXITCODE -ne 0) { throw 'Source archive creation failed.' }
    $sourceArchiveHash = (Get-FileHash -LiteralPath $sourceArchive).Hash.ToLowerInvariant()
    $targets = @(
        @{ platform = 'windows'; architecture = 'x64'; hostAlias = '' },
        @{ platform = 'macos'; architecture = 'arm64'; hostAlias = $MacArm64Host },
        @{ platform = 'macos'; architecture = 'x64'; hostAlias = $MacX64Host }
    )
    foreach ($target in $targets) {
        $archiveName = "wisprail-$version-$($target.platform)-$($target.architecture).zip"
        # Only these generated names belong to this invocation; leave unrelated files alone.
        foreach ($name in @($archiveName, ($archiveName + '.sha256'), ($archiveName + '.partial'))) {
            $old = Join-Path $dist $name
            if (Test-Path -LiteralPath $old -PathType Leaf) { Remove-Item -LiteralPath $old }
        }
    }
    foreach ($target in $targets) {
        $archiveName = "wisprail-$version-$($target.platform)-$($target.architecture).zip"
        $result = [ordered]@{ platform = $target.platform; architecture = $target.architecture; status = 'FAILED'; archive = $null; sha256 = $null; message = '' }
        try {
            if ($target.platform -eq 'windows') {
                $packageOutput = Join-Path $work 'windows-x64'
                & $powershell -NoProfile -File (Join-Path $source 'deploy\package-windows.ps1') -OutputDirectory $packageOutput -SourceSnapshot $sourceHash
                if ($LASTEXITCODE -ne 0) { throw 'Windows packaging failed.' }
                Copy-Item -LiteralPath (Join-Path $packageOutput $archiveName) -Destination (Join-Path $dist ($archiveName + '.partial'))
                Move-Item -LiteralPath (Join-Path $dist ($archiveName + '.partial')) -Destination (Join-Path $dist $archiveName)
            } elseif ($MacBuildMode -eq 'docker') {
                Invoke-MacCrossBuild $target.architecture $archiveName
            } else {
                Invoke-MacBuild $target.architecture $target.hostAlias $archiveName
            }
            $result.status = 'BUILT'
            $result.archive = $archiveName
            $result.sha256 = (Get-FileHash -LiteralPath (Join-Path $dist $archiveName) -Algorithm SHA256).Hash.ToLowerInvariant()
            ($result.sha256 + '  ' + $archiveName) | Set-Content -LiteralPath (Join-Path $dist ($archiveName + '.sha256')) -Encoding ASCII
        } catch {
            $result.message = $_.Exception.Message
            if ($result.message.StartsWith('BLOCKED:')) { $result.status = 'BLOCKED' }
            Write-Host "$archiveName : $($result.message)" -ForegroundColor Red
        }
        $results += $result
        $status = 'RUNNING'
        if ($results.Count -eq $targets.Count) {
            $status = if (@($results | Where-Object { $_.status -ne 'BUILT' }).Count -eq 0) { 'COMPLETE' } else { 'PARTIAL' }
        }
        [ordered]@{ version = $version; buildId = $buildId; status = $status; sourceSnapshot = $sourceHash; results = $results } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $dist 'build-results.json') -Encoding UTF8
    }
    $results | ForEach-Object { [pscustomobject]$_ } | Format-Table platform, architecture, status, archive
    if (@($results | Where-Object { $_.status -ne 'BUILT' }).Count -gt 0) { exit 1 }
} catch {
    [ordered]@{ buildId = $buildId; status = 'FAILED'; message = $_.Exception.Message; results = $results } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $dist 'build-results.json') -Encoding UTF8
    throw
} finally {
    $buildLock.Dispose()
}
