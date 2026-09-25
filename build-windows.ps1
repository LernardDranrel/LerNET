$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
& (Join-Path $root 'build-local.ps1') ':desktop-app:test' ':desktop-app:createDistributable' ':desktop-app:packageExe' '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw "Windows build failed: $LASTEXITCODE" }

$image = Join-Path $root 'desktop-app\build\compose\binaries\main\app\LerNET'
$artifactDirectory = Join-Path $root 'artifacts'
$archive = Join-Path $artifactDirectory 'LerNET-1.0.0-portable.zip'
$installer = Join-Path $artifactDirectory 'LerNET-1.0.0-install.exe'
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'packaging\windows\Start-LerNET.cmd') -Destination $image -Force
Copy-Item -LiteralPath (Join-Path $root 'packaging\windows\README-Windows.txt') -Destination $image -Force
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
Compress-Archive -LiteralPath $image -DestinationPath $archive -CompressionLevel Optimal

$installerCandidates = @(Get-ChildItem -LiteralPath (Join-Path $root 'desktop-app\build\compose\binaries\main\exe') -Filter '*.exe' -File)
if ($installerCandidates.Count -ne 1) { throw "Expected one Windows installer; found $($installerCandidates.Count)" }
Copy-Item -LiteralPath $installerCandidates[0].FullName -Destination $installer -Force
& (Join-Path $root 'smoke-test-windows.ps1') -Archive $archive
if ($LASTEXITCODE -ne 0) { throw "Windows smoke test failed: $LASTEXITCODE" }
Get-Item -LiteralPath $archive | Select-Object FullName,Length
Get-Item -LiteralPath $installer | Select-Object FullName,Length
Get-FileHash -LiteralPath $archive -Algorithm SHA256 | Select-Object Hash
Get-FileHash -LiteralPath $installer -Algorithm SHA256 | Select-Object Hash
