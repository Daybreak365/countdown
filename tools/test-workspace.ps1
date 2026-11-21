$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureBase = Join-Path $workspace ('.automation/tests/workflow test & path-' + [Guid]::NewGuid().ToString('N'))
$runner = Join-Path $PSScriptRoot 'workspace.ps1'
$utf8 = [Text.UTF8Encoding]::new($false)
function Assert($condition, [string]$message) { if (!$condition) { throw $message } }
function Jar([string]$path, [string]$id, [string]$version, [bool]$code = $true) {
    $zip = [IO.Compression.ZipFile]::Open($path, 'Create')
    try {
        $entry = $zip.CreateEntry('fabric.mod.json')
        $writer = [IO.StreamWriter]::new($entry.Open(), $utf8)
        try { $writer.Write((@{id=$id; version=$version; depends=@{minecraft='~26.3'; java='>=25'}} | ConvertTo-Json)) }
        finally { $writer.Dispose() }
        if ($code) {
            $stream = $zip.CreateEntry('example/Test.class').Open()
            try { $bytes = [byte[]]@(0xca,0xfe,0xba,0xbe,0,0,0,69); $stream.Write($bytes,0,$bytes.Length) }
            finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
}
function Fixture([string]$name) {
    $dir = Join-Path $fixtureBase $name
    foreach ($child in @('mod/build/libs','launcher/app/server-mods','launcher/app/assets','server/mods')) {
        $null = New-Item -ItemType Directory -Path (Join-Path $dir $child) -Force
    }
    Jar (Join-Path $dir 'mod/build/libs/newyear-countdown-9.9.9.jar') 'newyearcountdown' '9.9.9'
    (Get-Item -LiteralPath (Join-Path $dir 'mod/build/libs/newyear-countdown-9.9.9.jar')).LastWriteTimeUtc = [DateTime]::UtcNow.AddDays(-1)
    Jar (Join-Path $dir 'mod/build/libs/newyear-countdown-1.2.17.jar') 'newyearcountdown' '1.2.17'
    Jar (Join-Path $dir 'mod/build/libs/newyear-countdown-1.2.18-sources.jar') 'newyearcountdown' '1.2.18' $false
    foreach ($child in @('launcher/app/server-mods','server/mods')) {
        Jar (Join-Path $dir ($child+'/renamed-old.jar')) 'newyearcountdown' '1.2.16'
        Jar (Join-Path $dir ($child+'/fabric-api.jar')) 'fabric-api' '1.0'
    }
    [IO.File]::WriteAllText((Join-Path $dir 'launcher/app/assets/submitted-file.json'), '{"mod":{"name":"renamed-old.jar"},"fabricApi":{"sentinel":"keep"}}', $utf8)
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    [IO.File]::WriteAllText((Join-Path $dir 'server/server.properties'), "server-port=$port", $utf8)
    return $dir
}
function Run-Sync([string]$dir, [int]$expected, [switch]$Preview) {
    $params = @('-NoProfile','-ExecutionPolicy','Bypass','-File',$runner,'-Action','Sync','-WorkspaceRoot',$dir)
    if ($Preview) { $params += '-DryRun' }
    $output = & powershell.exe @params 2>&1
    Assert ($LASTEXITCODE -eq $expected) ("Unexpected exit code: $LASTEXITCODE; " + ($output -join "`n"))
}

$normal = Fixture 'normal'
$other = (Get-FileHash -LiteralPath (Join-Path $normal 'server/mods/fabric-api.jar')).Hash
$metadataPath = Join-Path $normal 'launcher/app/assets/submitted-file.json'
$metadataBefore = [IO.File]::ReadAllText($metadataPath)
Run-Sync $normal 0 -Preview
Assert (Test-Path -LiteralPath (Join-Path $normal 'server/mods/renamed-old.jar')) 'Dry run removed an old mod.'
Assert (!(Test-Path -LiteralPath (Join-Path $normal '.automation'))) 'Dry run wrote workflow state.'
Assert ([IO.File]::ReadAllText($metadataPath) -eq $metadataBefore) 'Dry run changed metadata.'
Run-Sync $normal 0
foreach ($child in @('launcher/app/server-mods','server/mods')) {
    Assert (Test-Path -LiteralPath (Join-Path $normal ($child+'/newyear-countdown-1.2.17.jar'))) 'Latest modification time was not selected.'
    Assert (!(Test-Path -LiteralPath (Join-Path $normal ($child+'/renamed-old.jar')))) 'Renamed old mod was not removed.'
    Assert (@(Get-ChildItem -LiteralPath (Join-Path $normal $child) -Filter '*.jar').Count -eq 2) 'Unwanted or duplicate mod was installed.'
}
Assert ((Get-FileHash -LiteralPath (Join-Path $normal 'server/mods/fabric-api.jar')).Hash -eq $other) 'Fabric API changed.'
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
Assert ($metadata.mod.version -eq '1.2.17' -and $metadata.mod.classCount -eq 1 -and $metadata.fabricApi.sentinel -eq 'keep') 'Metadata was not refreshed correctly.'
Assert ($metadata.mod.sha256 -eq (Get-FileHash -LiteralPath (Join-Path $normal 'server/mods/newyear-countdown-1.2.17.jar')).Hash.ToLowerInvariant()) 'Metadata hash differs.'
Run-Sync $normal 0
Write-Host 'PASS: dry run, newest build, sources excluded, renamed mod, unrelated mod, metadata, repeat sync.'

$rollback = Fixture 'rollback'
$meta = Join-Path $rollback 'launcher/app/assets/submitted-file.json'
$original = [IO.File]::ReadAllText($meta)
$held = [IO.File]::Open($meta, 'Open', 'Read', 'Read')
try { Run-Sync $rollback 1 } finally { $held.Dispose() }
foreach ($child in @('launcher/app/server-mods','server/mods')) {
    Assert (Test-Path -LiteralPath (Join-Path $rollback ($child+'/renamed-old.jar'))) 'Transaction did not restore the previous mod.'
    Assert (!(Test-Path -LiteralPath (Join-Path $rollback ($child+'/newyear-countdown-1.2.17.jar')))) 'Transaction left the new mod installed after failure.'
}
Assert ([IO.File]::ReadAllText($meta) -eq $original) 'Failed metadata write changed the original.'
Write-Host 'PASS: failed metadata write rolls both mod directories back.'

$busy = Fixture 'busy'
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
try {
    [IO.File]::WriteAllText((Join-Path $busy 'server/server.properties'), ('server-port='+$listener.LocalEndpoint.Port), $utf8)
    Run-Sync $busy 1
    Assert (Test-Path -LiteralPath (Join-Path $busy 'server/mods/renamed-old.jar')) 'Running server guard changed a mod.'
} finally { $listener.Stop() }
Write-Host 'PASS: occupied server port refuses changes.'

$bad = Join-Path $normal 'mod/build/libs/newyear-countdown-broken.jar'
[IO.File]::WriteAllText($bad, 'unfinished build', $utf8)
(Get-Item -LiteralPath $bad).LastWriteTimeUtc = [DateTime]::UtcNow.AddMinutes(1)
Run-Sync $normal 1
Assert (Test-Path -LiteralPath (Join-Path $normal 'server/mods/newyear-countdown-1.2.17.jar')) 'Invalid latest build changed installed mods.'
Write-Host 'PASS: incomplete newest build is rejected without falling back silently.'
Write-Host "Workflow tests passed. Fixtures: $fixtureBase"
