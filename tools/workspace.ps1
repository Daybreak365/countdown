param(
    [ValidateSet('Test', 'Build', 'Sync', 'Server', 'Launcher')][string]$Action = 'Test',
    [switch]$DryRun,
    [string]$WorkspaceRoot = (Split-Path -Parent $PSScriptRoot)
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath($WorkspaceRoot).TrimEnd('\', '/')
$utf8 = [Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Local-Path([string]$relative) {
    $path = [IO.Path]::GetFullPath((Join-Path $root $relative))
    if (!$path.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes workspace: $relative"
    }
    # Refuse junctions/symlinks before changing files or invoking build scripts.
    $part = $path
    while ($part -and $part.Length -ge $root.Length) {
        if ((Test-Path -LiteralPath $part) -and ((Get-Item -LiteralPath $part -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Linked paths are not supported: $part"
        }
        $part = [IO.Path]::GetDirectoryName($part)
    }
    return $path
}

function Read-Jar([string]$path) {
    $zip = [IO.Compression.ZipFile]::OpenRead($path)
    try {
        $entry = $zip.GetEntry('fabric.mod.json')
        if (!$entry) { return $null }
        if ($entry.Length -gt 1MB) { throw "Oversized mod metadata: $path" }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $meta = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        return [PSCustomObject]@{ Meta = $meta; Classes = @($zip.Entries | Where-Object { $_.FullName.EndsWith('.class') }).Count }
    } finally { $zip.Dispose() }
}

function Latest-Mod {
    $files = @(Get-ChildItem -LiteralPath (Local-Path 'mod/build/libs') -File -Filter 'newyear-countdown-*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|javadoc|dev|test|tests)\.jar$' } |
        Sort-Object @{Expression = 'LastWriteTimeUtc'; Descending = $true}, @{Expression = 'Name'; Descending = $true})
    if (!$files.Count) { throw 'No release JAR in mod/build/libs. Build the mod first.' }
    $file = $files[0]
    $null = Local-Path ('mod/build/libs/' + $file.Name)
    $jar = Read-Jar $file.FullName
    if (!$jar -or $jar.Meta.id -ne 'newyearcountdown' -or $jar.Classes -eq 0 -or $jar.Meta.version -match '\$\{') {
        throw "Latest JAR is not a valid built countdown mod: $($file.Name)"
    }
    return [PSCustomObject]@{ File = $file; Jar = $jar; Hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}

function Existing-Mods([string]$directory) {
    foreach ($file in Get-ChildItem -LiteralPath $directory -File -Filter '*.jar') {
        $null = Local-Path ($file.FullName.Substring($root.Length + 1))
        try { $jar = Read-Jar $file.FullName }
        catch {
            if ($file.Name -like 'newyear-countdown-*.jar') { throw "Cannot read $($file.FullName). Close the server/game and try again." }
            continue
        }
        if ($jar -and $jar.Meta.id -eq 'newyearcountdown') { $file }
    }
}

function Server-Port {
    $port = 25565
    foreach ($line in Get-Content -LiteralPath (Local-Path 'server/server.properties')) {
        if ($line -match '^\s*server-port\s*=\s*(\d+)\s*$') { $port = [int]$Matches[1] }
    }
    if ($port -lt 1 -or $port -gt 65535) { throw 'Invalid server-port in server/server.properties.' }
    return $port
}

function Check-ServerStopped {
    $port = Server-Port
    $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    if (@($listeners | Where-Object { $_.Port -eq $port }).Count) {
        throw "Port $port is already in use. Enter stop in the existing server console, then run test.bat again."
    }
    $pidFile = Local-Path '.automation/server-process.json'
    if (Test-Path -LiteralPath $pidFile) {
        $saved = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
        $process = Get-Process -Id $saved.id -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().ToString('O') -eq $saved.started) {
            throw 'The previous test server console is still open. Stop and close it before testing again.'
        }
    }
}

function Sync-Mod($latest, [string[]]$targets) {
    $backup = Local-Path ('.automation/backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6))
    $plans = @()
    $metadataPath = Local-Path 'launcher/app/assets/submitted-file.json'
    $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $metadata.mod = [ordered]@{
        name = $latest.File.Name; version = $latest.Jar.Meta.version; size = $latest.File.Length; sha256 = $latest.Hash
        minecraft = ([string]$latest.Jar.Meta.depends.minecraft -replace '^[~^= ]+', '')
        java = [int]([regex]::Match([string]$latest.Jar.Meta.depends.java, '\d+').Value)
        classCount = $latest.Jar.Classes; bundledAsSubmitted = $true
    }
    foreach ($relative in $targets) {
        $dir = Local-Path $relative
        if (!(Test-Path -LiteralPath $dir -PathType Container)) { throw "Missing mod directory: $dir" }
        $old = @(Existing-Mods $dir)
        $destination = Local-Path ($relative + '/' + $latest.File.Name)
        if ((Test-Path -LiteralPath $destination) -and $destination -notin @($old | ForEach-Object { $_.FullName })) {
            throw "Refusing to overwrite a different mod: $destination"
        }
        $plans += [PSCustomObject]@{
            Dir = $dir; Old = $old; Destination = $destination
            Staged = (Local-Path ($relative + '/' + $latest.File.Name + '.pending-' + [Guid]::NewGuid().ToString('N')))
            Backup = (Join-Path $backup ($relative.Replace('/', '_'))); Changed = $false; Installed = $false
        }
    }
    $metadataChanged = $false
    try {
        # Prepare every backup and verified copy before touching either installed mod.
        $null = New-Item -ItemType Directory -Path $backup -Force
        Copy-Item -LiteralPath $metadataPath -Destination (Join-Path $backup 'submitted-file.json')
        foreach ($plan in $plans) {
            $null = New-Item -ItemType Directory -Path $plan.Backup -Force
            foreach ($old in $plan.Old) { Copy-Item -LiteralPath $old.FullName -Destination (Join-Path $plan.Backup $old.Name) }
            Copy-Item -LiteralPath $latest.File.FullName -Destination $plan.Staged
            if ((Get-FileHash -LiteralPath $plan.Staged -Algorithm SHA256).Hash.ToLowerInvariant() -ne $latest.Hash) {
                throw 'The build changed while copying. Wait for the mod build to finish and retry.'
            }
        }
        foreach ($plan in $plans) {
            $plan.Changed = $true
            foreach ($old in $plan.Old) { Remove-Item -LiteralPath $old.FullName }
            Move-Item -LiteralPath $plan.Staged -Destination $plan.Destination
            $plan.Installed = $true
        }
        $metadataChanged = $true
        [IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json -Depth 20) + "`n", $utf8)
        foreach ($plan in $plans) { Write-Host "Updated: $($plan.Destination)" }
        Write-Host "Backup:  $backup"
    } catch {
        $original = $_
        foreach ($plan in $plans) {
            if (!$plan.Changed) { continue }
            try {
                if ($plan.Installed) { Remove-Item -LiteralPath $plan.Destination }
                foreach ($old in $plan.Old) { Copy-Item -LiteralPath (Join-Path $plan.Backup $old.Name) -Destination $old.FullName -Force }
            } catch { Write-Warning "Restore failed for $($plan.Dir). Recover the originals from $backup" }
        }
        if ($metadataChanged) {
            try { Copy-Item -LiteralPath (Join-Path $backup 'submitted-file.json') -Destination $metadataPath -Force }
            catch { Write-Warning "Restore submitted-file.json from $backup" }
        }
        throw $original
    } finally {
        foreach ($plan in $plans) {
            if (Test-Path -LiteralPath $plan.Staged) { Remove-Item -LiteralPath $plan.Staged -ErrorAction SilentlyContinue }
        }
    }
}

function Invoke-Npm([string[]]$NpmArgs, [string]$directory) {
    Push-Location -LiteralPath $directory
    try {
        & npm.cmd @NpmArgs
        if ($LASTEXITCODE -ne 0) { throw "npm $($NpmArgs -join ' ') failed (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
}

function Ensure-Dependencies {
    $launcher = Local-Path 'launcher'
    if (!(Test-Path -LiteralPath (Local-Path 'launcher/node_modules/electron/dist/electron.exe')) -or
        !(Test-Path -LiteralPath (Local-Path 'launcher/node_modules/@electron/packager/package.json')) -or
        !(Test-Path -LiteralPath (Local-Path 'launcher/node_modules/electron-builder/package.json'))) {
        Invoke-Npm @('ci') $launcher
    }
    if (!(Test-Path -LiteralPath (Local-Path 'launcher/app/node_modules/@xmcl/core/package.json')) -or
        !(Test-Path -LiteralPath (Local-Path 'launcher/app/node_modules/adm-zip/package.json'))) {
        Invoke-Npm @('ci') (Local-Path 'launcher/app')
    }
    # Also repair the existing XMCL package when dependencies were already installed.
    Push-Location -LiteralPath (Local-Path 'launcher/app')
    try { & node fix-runtime-deps.cjs; if ($LASTEXITCODE -ne 0) { throw 'Launcher dependency repair failed.' } }
    finally { Pop-Location }
}

function Open-Console([string]$mode) {
    $script = Local-Path 'tools/workspace.ps1'
    # Process arguments use Windows quoting, not a shell-built command string.
    return Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', ('"' + $script + '"'), '-Action', $mode, '-WorkspaceRoot', ('"' + $root + '"')) -WorkingDirectory $root -WindowStyle Normal -PassThru
}

$lock = $null
try {
    if ($Action -in @('Server', 'Launcher')) {
        if ($DryRun) { Write-Host "Would open $Action console."; exit 0 }
        $Host.UI.RawUI.WindowTitle = "Countdown - $Action"
        if ($Action -eq 'Server') {
            Set-Location -LiteralPath (Local-Path 'server')
            & cmd.exe /d /c run.bat
        } else {
            $env:ELECTRON_RUN_AS_NODE = $null
            Invoke-Npm @('start') (Local-Path 'launcher')
        }
        exit $LASTEXITCODE
    }
    $latest = Latest-Mod
    $targets = if ($Action -eq 'Build') { @('launcher/app/server-mods') } else { @('launcher/app/server-mods', 'server/mods') }
    Write-Host "Latest release: $($latest.File.Name) ($($latest.Jar.Meta.version))"
    Write-Host "Build time:     $($latest.File.LastWriteTime)"
    Write-Host "SHA256:         $($latest.Hash)"
    foreach ($target in $targets) {
        $dir = Local-Path $target
        $old = @(Existing-Mods $dir)
        Write-Host "Target: $target; replace: $(($old | ForEach-Object { $_.Name }) -join ', ')"
    }
    if ($DryRun) { Write-Host "Dry run: no files changed, no programs started. Action: $Action"; exit 0 }
    $stateDir = Local-Path '.automation'
    $null = New-Item -ItemType Directory -Path $stateDir -Force
    try { $lock = [IO.File]::Open((Local-Path '.automation/workflow.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another test/build/sync is running. Wait for it to finish.' }
    if ($Action -in @('Test', 'Sync')) { Check-ServerStopped }
    if ($Action -ne 'Sync') {
        foreach ($command in @('node', 'npm.cmd')) { if (!(Get-Command $command -ErrorAction SilentlyContinue)) { throw "Install Node.js 24+ and npm first: missing $command" } }
        $nodeMajor = [int]((& node -p 'parseInt(process.versions.node,10)') | Select-Object -Last 1)
        if ($LASTEXITCODE -ne 0 -or $nodeMajor -lt 24) { throw 'Node.js 24 or newer is required.' }
        if ($Action -eq 'Test') {
            if (!(Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install Java 25 and add it to PATH first.' }
            $javaVersion = @(& java --version)
            if ($LASTEXITCODE -ne 0 -or !$javaVersion.Count -or $javaVersion[0] -notmatch '^(?:openjdk|java)\s+(\d+)' -or [int]$Matches[1] -lt 25) {
                throw 'Java 25 or newer must be selected on PATH to run this server.'
            }
            if (!(Test-Path -LiteralPath (Local-Path 'server/run.bat')) -or !(Test-Path -LiteralPath (Local-Path 'server/fabric-server.jar'))) { throw 'Missing server/run.bat or server/fabric-server.jar.' }
        }
        Ensure-Dependencies
    }
    Sync-Mod $latest $targets
    if ($Action -eq 'Sync') { Write-Host 'Mod synchronization complete.'; exit 0 }
    if ($Action -eq 'Build') {
        Invoke-Npm @('test') (Local-Path 'launcher')
        Invoke-Npm @('run', 'build:portable') (Local-Path 'launcher')
        $output = Local-Path 'launcher/portable-build/2027-Launcher-Portable.exe'
        if (!(Test-Path -LiteralPath $output)) { throw 'Build completed without the expected portable EXE.' }
        Write-Host "Build complete: $output"
        exit 0
    }
    $started = [DateTime]::UtcNow
    $server = Open-Console 'Server'
    [IO.File]::WriteAllText((Local-Path '.automation/server-process.json'), (@{id = $server.Id; started = $server.StartTime.ToUniversalTime().ToString('O')} | ConvertTo-Json), $utf8)
    Write-Host 'Waiting for the Minecraft server to finish starting (up to 120 seconds)...'
    $log = Local-Path 'server/logs/latest.log'
    $ready = $false
    while ([DateTime]::UtcNow -lt $started.AddSeconds(120)) {
        if ($server.HasExited) { throw 'Server console exited before startup completed.' }
        if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).LastWriteTimeUtc -ge $started) {
            if (@(Get-Content -LiteralPath $log -Tail 100 -ErrorAction SilentlyContinue | Select-String 'Done \([^)]+\)!').Count) { $ready = $true; break }
        }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'Server did not become ready. Check its console and server/logs/latest.log. Stop and close that console before retrying.' }
    $null = Open-Console 'Launcher'
    Write-Host 'Server ready. Launcher started. Sign in and press Play in the launcher.'
    Write-Host 'To finish testing, close the game/launcher and enter stop in the server console.'
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    if ($Action -in @('Server', 'Launcher')) { $null = Read-Host 'Press Enter to close this console' }
    exit 1
} finally {
    if ($lock) { $lock.Dispose() }
}
