param([Parameter(Mandatory=$true)][string]$InstallDir, [string]$LegacyProduct = '', [switch]$FunctionsOnly)
$ErrorActionPreference = 'Stop'

function Get-LerNetOwnedProcesses($Snapshot, [string]$Executable) {
    $roots = @($Snapshot | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.Equals($Executable, [StringComparison]::OrdinalIgnoreCase) })
    $owned = @{}
    foreach ($item in $roots) { $owned[[int]$item.ProcessId] = $item }
    # Only descendants of the exact installed executable; never a global sing-box kill.
    for ($depth = 0; $depth -lt 32; $depth++) {
        $changed = $false
        foreach ($item in $Snapshot) {
            if ($owned.ContainsKey([int]$item.ParentProcessId) -and -not $owned.ContainsKey([int]$item.ProcessId)) {
                $parent = $owned[[int]$item.ParentProcessId]
                # A child older than its supposed parent belongs to a reused parent PID.
                if ($item.CreationDate -and $parent.CreationDate -and $item.CreationDate -lt $parent.CreationDate) { continue }
                $owned[[int]$item.ProcessId] = $item; $changed = $true
            }
        }
        if (-not $changed) { break }
    }
    return @($Snapshot | Where-Object { $owned.ContainsKey([int]$_.ProcessId) -and
        ($_.ExecutablePath -eq $Executable -or $_.Name -in @('sing-box.exe', 'java.exe', 'javaw.exe')) })
}

function Get-LerNetEventName([string]$Executable) {
    $canonical = [IO.Path]::GetFullPath($Executable).ToLowerInvariant()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)) }
    finally { $sha.Dispose() }
    return 'Local\LerNET.Update.' + ([BitConverter]::ToString($hash).Replace('-', '').ToLowerInvariant())
}

if ($FunctionsOnly) { return }
try {
    $executable = Join-Path ([IO.Path]::GetFullPath($InstallDir)) 'LerNET.exe'
    $snapshot = @(Get-CimInstance Win32_Process)
    $owned = @(Get-LerNetOwnedProcesses $snapshot $executable)
    if ($owned.Count -gt 0) {
        try {
            $signal = [Threading.EventWaitHandle]::OpenExisting((Get-LerNetEventName $executable))
            try { [void]$signal.Set() } finally { $signal.Dispose() }
            $deadline = [DateTime]::UtcNow.AddSeconds(15)
            while ([DateTime]::UtcNow -lt $deadline -and (Get-Process -Id @($owned.ProcessId) -ErrorAction SilentlyContinue)) {
                Start-Sleep -Milliseconds 250
            }
        } catch [Threading.WaitHandleCannotBeOpenedException] {
            # Older versions have no shutdown signal. Ask to close first, then scoped fallback.
            foreach ($item in $owned | Where-Object { $_.ExecutablePath -eq $executable }) {
                $process = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
                if ($process) { [void]$process.CloseMainWindow() }
            }
            Start-Sleep -Seconds 2
        }
        # Snapshot creation time guards against a reused PID. Stop children before launchers.
        $ordered = @($owned | Sort-Object @{Expression={ if ($_.ExecutablePath -eq $executable) { 1 } else { 0 } }})
        foreach ($item in $ordered) {
            $current = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$item.ProcessId)"
            if ($current -and $current.CreationDate -eq $item.CreationDate -and $current.ExecutablePath -eq $item.ExecutablePath) {
                Stop-Process -Id $item.ProcessId -Force
            }
        }
        foreach ($item in $owned) {
            Wait-Process -Id $item.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
            $current = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$item.ProcessId)"
            if ($current -and $current.CreationDate -eq $item.CreationDate) { throw 'LerNET is still running' }
        }
    }
    if ($LegacyProduct) {
        if ($LegacyProduct -notmatch '^\{[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\}$') { throw 'Invalid legacy product id' }
        $keys = @('HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\')
        $product = @($keys | ForEach-Object { Get-ItemProperty -LiteralPath ($_ + $LegacyProduct) -ErrorAction SilentlyContinue })
        if ($product.Count -ne 1 -or $product[0].DisplayName -ne 'LerNET' -or $product[0].WindowsInstaller -ne 1 -or
            [IO.Path]::GetFullPath($product[0].InstallLocation).TrimEnd('\') -ne [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')) {
            throw 'Legacy installation does not match the selected directory'
        }
        $uninstall = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList @('/x', $LegacyProduct, '/qn', '/norestart') -WindowStyle Hidden -Wait -PassThru
        if ($uninstall.ExitCode -eq 3010) { exit 10 }
        if ($uninstall.ExitCode -ne 0) { throw "Legacy uninstall failed: $($uninstall.ExitCode)" }
    }
    exit 0
} catch {
    # Setup logs the exit code. Never print process command lines or profile data.
    exit 1
}
