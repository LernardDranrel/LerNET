$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$versionLine = Get-Content -LiteralPath (Join-Path $root 'gradle.properties') |
    Where-Object { $_ -match '^lernetVersion=' } | Select-Object -First 1
if (-not $versionLine) { throw 'lernetVersion is missing from gradle.properties' }
$appVersion = $versionLine.Substring('lernetVersion='.Length).Trim()
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { throw 'Python 3 is required to create the local release key' }
& $python.Source (Join-Path $PSScriptRoot 'ensure-release-key.py')
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare Android release key' }

& (Join-Path $root 'build-local.ps1') ':app:assembleRelease' '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw 'Android release build failed' }

$source = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
$target = Join-Path $root "artifacts\LerNET-$appVersion.apk"
if (-not (Test-Path -LiteralPath $source)) { throw "Signed APK is missing: $source" }
New-Item -ItemType Directory -Path (Join-Path $root 'artifacts') -Force | Out-Null
Copy-Item -LiteralPath $source -Destination $target -Force

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $root 'android-sdk' }
$apksigner = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Filter 'apksigner.bat' -Recurse -File |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $apksigner) { throw 'Android apksigner is missing' }
& $apksigner.FullName verify --verbose $target
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
Get-Item -LiteralPath $target | Select-Object FullName,Length
Get-FileHash -LiteralPath $target -Algorithm SHA256 | Select-Object Hash
