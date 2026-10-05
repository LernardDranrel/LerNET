param([switch]$PackageOnly)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$versionLine = Get-Content -LiteralPath (Join-Path $root 'gradle.properties') |
    Where-Object { $_ -match '^lernetVersion=' } | Select-Object -First 1
if (-not $versionLine) { throw 'lernetVersion is missing from gradle.properties' }
$appVersion = $versionLine.Substring('lernetVersion='.Length).Trim()
$tasks = if ($PackageOnly) {
    @(':desktop-app:createDistributable', '--offline', '--no-daemon')
} else {
    @(':desktop-app:test', ':desktop-app:createDistributable', '--no-daemon')
}
& (Join-Path $root 'build-local.ps1') @tasks
if ($LASTEXITCODE -ne 0) { throw "Windows build failed: $LASTEXITCODE" }

$image = Join-Path $root 'desktop-app\build\compose\binaries\main\app\LerNET'
$artifactDirectory = Join-Path $root 'artifacts'
$archive = Join-Path $artifactDirectory "LerNET-$appVersion-portable.zip"
$installer = Join-Path $artifactDirectory "LerNET-$appVersion-install.exe"
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'packaging\windows\Start-LerNET.cmd') -Destination $image -Force
Copy-Item -LiteralPath (Join-Path $root 'packaging\windows\README-Windows.txt') -Destination $image -Force
Copy-Item -LiteralPath (Join-Path $root 'packaging\windows\UpdateProcesses.ps1') -Destination $image -Force
Copy-Item -LiteralPath (Join-Path $root 'desktop-app\src\main\resources\runtime\lernet-protection-service.exe') -Destination $image -Force
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
Compress-Archive -LiteralPath $image -DestinationPath $archive -CompressionLevel Optimal

$compilerCandidates = @($env:LERNET_ISCC,
    (Join-Path $root 'agent-tools\inno\compiler\ISCC.exe'),
    (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'))
$compiler = $compilerCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $compiler) { throw 'Install Inno Setup 6.7.3 or set LERNET_ISCC to ISCC.exe. See packaging/windows/README.md' }
& $compiler "/DAppVersion=$appVersion" "/DImageDir=$image" "/DOutputDir=$artifactDirectory" (Join-Path $root 'packaging\windows\LerNET.iss')
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $installer)) { throw 'Inno Setup compilation failed' }
if (-not $PackageOnly) {
    & (Join-Path $root 'smoke-test-windows.ps1') -Archive $archive
    if ($LASTEXITCODE -ne 0) { throw "Windows smoke test failed: $LASTEXITCODE" }
}
Get-Item -LiteralPath $archive | Select-Object FullName,Length
Get-Item -LiteralPath $installer | Select-Object FullName,Length
Get-FileHash -LiteralPath $archive -Algorithm SHA256 | Select-Object Hash
Get-FileHash -LiteralPath $installer -Algorithm SHA256 | Select-Object Hash
