param([Parameter(Mandatory = $true)][string]$Archive)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$artifactDirectory = Join-Path $root 'artifacts'
$smokeDirectory = Join-Path $artifactDirectory '_windows_smoke_test'
if (Test-Path -LiteralPath $smokeDirectory) {
    throw "Smoke directory already exists: $smokeDirectory"
}

$launched = @()
try {
    Expand-Archive -LiteralPath $archivePath -DestinationPath $smokeDirectory
    $image = Join-Path $smokeDirectory 'LerNET'
    foreach ($relativePath in @('LerNET.exe', 'app\LerNET.cfg', 'runtime\bin\server\jvm.dll', 'Start-LerNET.cmd', 'README-Windows.txt')) {
        if (-not (Test-Path -LiteralPath (Join-Path $image $relativePath))) {
            throw "Windows archive is missing $relativePath"
        }
    }
    if (-not ((Get-Content -LiteralPath (Join-Path $image 'runtime\release') -Raw) -match 'java.net.http')) {
        throw 'Windows runtime is missing java.net.http'
    }

    $exe = Join-Path $image 'LerNET.exe'
    $marker = Join-Path $smokeDirectory 'startup-ready.txt'
    $launcher = Start-Process -FilePath $exe -ArgumentList "--verify-startup=`"$marker`"" -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 500
    } while (-not (Test-Path -LiteralPath $marker) -and (Get-Date) -lt $deadline)
    if (-not (Test-Path -LiteralPath $marker)) { throw 'Windows application did not complete UI startup' }
    if ((Get-Content -LiteralPath $marker -Raw) -ne 'ready') { throw 'Windows startup marker is invalid' }
    Write-Output 'Windows archive smoke test passed: application UI completed startup.'
} finally {
    $launched = @(Get-Process -Name 'LerNET' -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$smokeDirectory*" })
    $launched | Stop-Process -Force -ErrorAction SilentlyContinue
    $launched | Wait-Process -Timeout 10 -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $smokeDirectory) {
        $resolvedSmokeDirectory = (Resolve-Path -LiteralPath $smokeDirectory).Path
        if ($resolvedSmokeDirectory -ne $smokeDirectory -or $resolvedSmokeDirectory -notlike "$artifactDirectory\*") {
            throw "Refusing to remove unexpected smoke directory: $resolvedSmokeDirectory"
        }
        for ($attempt = 0; $attempt -lt 20; $attempt++) {
            try {
                Remove-Item -LiteralPath $resolvedSmokeDirectory -Recurse -Force -ErrorAction Stop
                break
            } catch {
                if ($attempt -eq 19) { throw }
                Start-Sleep -Milliseconds 500
            }
        }
    }
}
