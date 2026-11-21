param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [ValidateSet('x86_64','arm64')][string]$Architecture = 'x86_64',
    [switch]$Bundle
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
if (!(Test-Path -LiteralPath (Join-Path $sdk 'include/discordpp.h'))) { throw 'SdkRoot must contain include/discordpp.h from the official C++ SDK.' }
$cmake = Get-Command cmake -ErrorAction SilentlyContinue
if (!$cmake) {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    if (Test-Path -LiteralPath $vswhere) {
        $vsRoot = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ($vsRoot) {
            $bundledCmake = Join-Path $vsRoot 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
            if (Test-Path -LiteralPath $bundledCmake) { $cmake = Get-Command $bundledCmake }
        }
    }
}
if (!$cmake) { throw 'Install CMake and the Visual Studio C++ build tools, then rerun this script in a developer shell.' }
$build = Join-Path $project ('build/discord-social/windows-'+$Architecture)
$cmakeArch = if ($Architecture -eq 'arm64') { 'ARM64' } else { 'x64' }
$suffix = if ($Architecture -eq 'arm64') { '/arm64' } else { '' }
& $cmake.Source -S (Join-Path $project 'native/discord-social') -B $build -A $cmakeArch "-DDISCORD_SDK_ROOT=$sdk" "-DSDK_PLATFORM_SUFFIX=$suffix"
if ($LASTEXITCODE -ne 0) { throw 'Social SDK bridge configuration failed.' }
& $cmake.Source --build $build --config Release
if ($LASTEXITCODE -ne 0) { throw 'Social SDK bridge compilation failed.' }
$output = Join-Path $build 'Release'
foreach ($name in @('newyear-discord-social.exe','discord_partner_sdk.dll')) {
    if (!(Test-Path -LiteralPath (Join-Path $output $name))) { throw "Missing runtime file: $name" }
}
if ($Bundle) {
    $bundleDir = Join-Path $project ('src/main/resources/native/discord-social/windows-'+$Architecture)
    [IO.Directory]::CreateDirectory($bundleDir) | Out-Null
    foreach ($name in @('newyear-discord-social.exe','discord_partner_sdk.dll')) {
        Copy-Item -LiteralPath (Join-Path $output $name) -Destination (Join-Path $bundleDir $name) -Force
    }
    # Keep any SDK license/notice files with the distributed application.
    foreach ($notice in Get-ChildItem -LiteralPath $sdk -File | Where-Object { $_.Name -match 'LICENSE|NOTICE|COPYING' }) {
        Copy-Item -LiteralPath $notice.FullName -Destination (Join-Path $bundleDir $notice.Name) -Force
    }
    Write-Output "Native runtime added to mod resources: $bundleDir"
}
Get-ChildItem -LiteralPath $output -File | Where-Object { $_.Name -in @('newyear-discord-social.exe','discord_partner_sdk.dll') } | Select-Object Name,Length
