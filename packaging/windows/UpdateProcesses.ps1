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
        ($_.ExecutablePath -eq $Executable -or $_.Name -in @('sing-box.exe', 'lernet-core.exe', 'java.exe', 'javaw.exe')) })
}

function Get-LerNetEventName([string]$Executable) {
    $canonical = [IO.Path]::GetFullPath($Executable).ToLowerInvariant()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)) }
    finally { $sha.Dispose() }
    return 'Local\LerNET.Update.' + ([BitConverter]::ToString($hash).Replace('-', '').ToLowerInvariant())
}

function Get-LerNetProcessOperations {
    # Process.Handle pins the kernel object. .NET Framework Kill and WaitForExit
    # reuse that stored handle; never close/refresh it and reopen by PID.
    return @{
        Open = { param($ProcessId)
            try { return [Diagnostics.Process]::GetProcessById($ProcessId) }
            catch [ArgumentException] { return $null } # Already disappeared.
        }
        Pin = { param($Process) [void]$Process.Handle }
        Identity = { param($Process)
            return [pscustomobject]@{ CreationDate=$Process.StartTime; ExecutablePath=$Process.MainModule.FileName }
        }
        Exited = { param($Process) return $Process.HasExited }
        Stop = { param($Process) $Process.Kill() }
        Wait = { param($Process, $Milliseconds) return $Process.WaitForExit($Milliseconds) }
        Dispose = { param($Process) $Process.Dispose() }
    }
}

function Open-LerNetOwnedProcessLease($Item, $Operations) {
    if (-not $Item.CreationDate -or -not $Item.ExecutablePath) { throw 'Missing owned process identity' }
    $process = & $Operations.Open ([int]$Item.ProcessId)
    if ($null -eq $process) { return $null }
    $retained = $false
    try {
        & $Operations.Pin $process
        if (& $Operations.Exited $process) { return $null }
        $identity = & $Operations.Identity $process
        # Win32_Process CreationDate has microsecond precision; Process.StartTime
        # can also have a seventh fractional digit. Compare at the snapshot precision.
        $format = 'yyyyMMddHHmmssffffff'
        $culture = [Globalization.CultureInfo]::InvariantCulture
        $expectedTime = ([datetime]$Item.CreationDate).ToUniversalTime().ToString($format, $culture)
        $actualTime = ([datetime]$identity.CreationDate).ToUniversalTime().ToString($format, $culture)
        if (-not $identity.ExecutablePath -or $expectedTime -cne $actualTime -or
            -not $identity.ExecutablePath.Equals($Item.ExecutablePath, [StringComparison]::OrdinalIgnoreCase)) {
            return $null # Reused PID or another executable: never mutate it.
        }
        $retained = $true
        return [pscustomobject]@{ Process=$process; Item=$Item }
    } catch {
        $failure = $_
        $exited = $false
        try { $exited = & $Operations.Exited $process } catch { $exited = $false }
        if (-not $exited) { throw $failure }
    } finally {
        if (-not $retained) { & $Operations.Dispose $process }
    }
}

function Stop-LerNetProcessLease($Lease, $Operations) {
    try {
        if (-not (& $Operations.Exited $Lease.Process)) { & $Operations.Stop $Lease.Process }
    } catch {
        $failure = $_
        $exited = $false
        try { $exited = & $Operations.Exited $Lease.Process } catch { $exited = $false }
        # A graceful exit racing Kill is success only when this retained object exited.
        if (-not $exited) { throw $failure }
    }
}

function Confirm-LerNetProcessLeaseExit($Lease, $Operations) {
    if (-not (& $Operations.Wait $Lease.Process 10000) -and
        -not (& $Operations.Exited $Lease.Process)) { throw 'LerNET is still running' }
}

function Get-LerNetPreparationFailure([string]$Stage, $Failure, [bool]$HasLegacyProduct) {
    # Exception messages and invocation text may contain private paths or arguments.
    return [ordered]@{
        stage = $Stage
        exceptionType = $Failure.Exception.GetType().FullName
        errorId = $Failure.FullyQualifiedErrorId
        scriptLine = $Failure.InvocationInfo.ScriptLineNumber
        categoryReason = $Failure.CategoryInfo.Reason
        legacyProductSupplied = $HasLegacyProduct
    }
}

if ($FunctionsOnly) { return }
$lernetPreparationStage = 'resolve-installation'
try {
    $executable = Join-Path ([IO.Path]::GetFullPath($InstallDir)) 'LerNET.exe'
    $lernetPreparationStage = 'snapshot-processes'
    $snapshot = @(Get-CimInstance Win32_Process)
    $owned = @(Get-LerNetOwnedProcesses $snapshot $executable)
    if ($owned.Count -gt 0) {
        $operations = Get-LerNetProcessOperations
        $leases = @()
        try {
            $lernetPreparationStage = 'lease-owned-process'
            foreach ($item in $owned) {
                $lease = Open-LerNetOwnedProcessLease $item $operations
                if ($null -ne $lease) { $leases += $lease }
            }
            if ($leases.Count -gt 0) {
                try {
                    $lernetPreparationStage = 'signal-shutdown'
                    $signal = [Threading.EventWaitHandle]::OpenExisting((Get-LerNetEventName $executable))
                    try { [void]$signal.Set() } finally { $signal.Dispose() }
                    $deadline = [DateTime]::UtcNow.AddSeconds(15)
                    while ([DateTime]::UtcNow -lt $deadline -and
                        @($leases | Where-Object { -not (& $operations.Exited $_.Process) }).Count -gt 0) {
                        Start-Sleep -Milliseconds 250
                    }
                } catch [Threading.WaitHandleCannotBeOpenedException] {
                    # Legacy versions have no event. Do not post to a potentially reused
                    # HWND; the verified retained handles provide the scoped fallback.
                    $lernetPreparationStage = 'legacy-shutdown-grace'
                    Start-Sleep -Seconds 2
                }
                $ordered = @($leases | Sort-Object @{Expression={ if ($_.Item.ExecutablePath -eq $executable) { 1 } else { 0 } }})
                $lernetPreparationStage = 'stop-owned-process'
                foreach ($lease in $ordered) { Stop-LerNetProcessLease $lease $operations }
                $lernetPreparationStage = 'confirm-process-exit'
                foreach ($lease in $leases) { Confirm-LerNetProcessLeaseExit $lease $operations }
            }
        } finally {
            foreach ($lease in $leases) { & $operations.Dispose $lease.Process }
        }
    }
    if ($LegacyProduct) {
        $lernetPreparationStage = 'verify-legacy-installation'
        if ($LegacyProduct -notmatch '^\{[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\}$') { throw 'Invalid legacy product id' }
        $keys = @('HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\')
        $product = @($keys | ForEach-Object { Get-ItemProperty -LiteralPath ($_ + $LegacyProduct) -ErrorAction SilentlyContinue })
        if ($product.Count -ne 1 -or $product[0].DisplayName -ne 'LerNET' -or $product[0].WindowsInstaller -ne 1 -or
            [IO.Path]::GetFullPath($product[0].InstallLocation).TrimEnd('\') -ne [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')) {
            throw 'Legacy installation does not match the selected directory'
        }
        $lernetPreparationStage = 'uninstall-legacy'
        $uninstall = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList @('/x', $LegacyProduct, '/qn', '/norestart') -WindowStyle Hidden -Wait -PassThru
        if ($uninstall.ExitCode -eq 3010) { exit 10 }
        if ($uninstall.ExitCode -ne 0) { throw "Legacy uninstall failed: $($uninstall.ExitCode)" }
    }
    exit 0
} catch {
    $lernetFailure = Get-LerNetPreparationFailure $lernetPreparationStage $_ ([bool]$LegacyProduct)
    [Console]::Error.WriteLine('LerNET_PREPARATION_FAILURE ' + ($lernetFailure | ConvertTo-Json -Compress))
    exit 1
}
