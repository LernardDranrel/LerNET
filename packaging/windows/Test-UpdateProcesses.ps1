$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'UpdateProcesses.ps1') -InstallDir 'C:\Fixtures\LerNET' -FunctionsOnly
$fixtures = @(
    [pscustomobject]@{ CreationDate=[datetime]'2026-01-02'; ProcessId=10; ParentProcessId=1; Name='LerNET.exe'; ExecutablePath='C:\Fixtures\LerNET\LerNET.exe' },
    [pscustomobject]@{ ProcessId=11; ParentProcessId=10; Name='sing-box.exe'; ExecutablePath='C:\Data\LerNET\sing-box.exe' },
    [pscustomobject]@{ CreationDate=[datetime]'2026-01-03'; ProcessId=14; ParentProcessId=10; Name='lernet-core.exe'; ExecutablePath='C:\Program Files\LerNETProtection\verified-bundle\lernet-core.exe' },
    [pscustomobject]@{ ProcessId=12; ParentProcessId=10; Name='setup.exe'; ExecutablePath='C:\Temp\setup.exe' },
    [pscustomobject]@{ ProcessId=13; ParentProcessId=12; Name='powershell.exe'; ExecutablePath='C:\Windows\powershell.exe' },
    [pscustomobject]@{ ProcessId=20; ParentProcessId=1; Name='LerNET.exe'; ExecutablePath='C:\Portable\LerNET.exe' },
    [pscustomobject]@{ ProcessId=21; ParentProcessId=20; Name='sing-box.exe'; ExecutablePath='C:\Portable\sing-box.exe' },
    [pscustomobject]@{ ProcessId=22; ParentProcessId=20; Name='lernet-core.exe'; ExecutablePath='C:\Portable\lernet-core.exe' },
    [pscustomobject]@{ CreationDate=[datetime]'2026-01-01'; ProcessId=31; ParentProcessId=10; Name='sing-box.exe'; ExecutablePath='C:\OtherVPN\sing-box.exe' },
    [pscustomobject]@{ ProcessId=30; ParentProcessId=1; Name='sing-box.exe'; ExecutablePath='C:\OtherVPN\sing-box.exe' }
)
$actual = @(Get-LerNetOwnedProcesses $fixtures 'C:\Fixtures\LerNET\LerNET.exe')
if (($actual.ProcessId -join ',') -ne '10,11,14') { throw 'Selected unrelated processes or missed protected core' }
if (@(Get-LerNetOwnedProcesses $fixtures 'C:\Unknown\LerNET.exe').Count -ne 0) { throw 'Selected missing installation' }
$signal = Get-LerNetEventName 'C:\Fixtures\LerNET\LerNET.exe'
if ($signal -ne (Get-LerNetEventName 'c:\fixtures\lernet\LerNET.exe')) { throw 'Event is case-sensitive' }
if ($signal -eq (Get-LerNetEventName 'C:\Portable\LerNET.exe')) { throw 'Event is not scoped to installation' }
$failure = [Management.Automation.ErrorRecord]::new(
    [InvalidOperationException]::new('private-profile-token'),
    'FixtureFailure', [Management.Automation.ErrorCategory]::InvalidOperation, 'private-profile-data')
$diagnostic = Get-LerNetPreparationFailure 'stop-owned-process' $failure $false | ConvertTo-Json -Compress
if ($diagnostic.Contains('private-profile')) { throw 'Preparation diagnostic leaked private exception or target data' }
if (-not $diagnostic.Contains('FixtureFailure') -or -not $diagnostic.Contains('stop-owned-process')) {
    throw 'Preparation diagnostic omitted its actionable stage or error id'
}

# Scriptblock operations exercise the production lease policy without opening
# OS handles, querying processes/registry, closing windows or killing anything.
function New-FixtureProcess([datetime]$Created, [string]$Path) {
    return @{ Created=$Created; Path=$Path; Pinned=$false; Exited=$false;
        Stops=0; Disposals=0; ExitDuringPin=$false; DenyPin=$false; ExitDuringStop=$false; DenyStop=$false }
}
function New-FixtureOperations($Lookup) {
    return @{
        Open = { param($ProcessId)
            $Lookup.Opens++
            if ($Lookup.DenyOpen) { throw 'FixtureOpenAccessDenied' }
            return $Lookup.Current
        }.GetNewClosure()
        Pin = { param($Process)
            if ($Process.ExitDuringPin) { $Process.Exited=$true; throw 'FixtureExitedBeforeHandleOpened' }
            if ($Process.DenyPin) { throw 'FixtureHandleAccessDenied' }
            $Process.Pinned=$true
        }
        Identity = { param($Process)
            if (-not $Process.Pinned) { throw 'Identity queried before retaining the handle' }
            return [pscustomobject]@{ CreationDate=$Process.Created; ExecutablePath=$Process.Path }
        }
        Exited = { param($Process) return $Process.Exited }
        Stop = { param($Process)
            if (-not $Process.Pinned) { throw 'Stopped without a retained handle' }
            $Process.Stops++
            if ($Process.ExitDuringStop) { $Process.Exited=$true; throw 'FixtureAlreadyExited' }
            if ($Process.DenyStop) { throw 'FixtureStopAccessDenied' }
            $Process.Exited=$true
        }
        Wait = { param($Process, $Milliseconds) return $Process.Exited }
        Dispose = { param($Process) $Process.Disposals++ }
    }
}
$item = [pscustomobject]@{ ProcessId=10; CreationDate=[datetime]'2026-01-02T03:04:05.123456'; ExecutablePath='C:\Fixtures\LerNET\LerNET.exe' }
$lookup = @{ Current=$null; Opens=0; DenyOpen=$false }
$ops = New-FixtureOperations $lookup
if ($null -ne (Open-LerNetOwnedProcessLease $item $ops)) { throw 'Disappeared process retained' }

foreach ($pinRace in @('exited', 'denied')) {
    $state = New-FixtureProcess $item.CreationDate $item.ExecutablePath
    $lookup.Current=$state
    $state.ExitDuringPin=($pinRace -eq 'exited'); $state.DenyPin=($pinRace -eq 'denied')
    $failed=$false; $lease=$null
    try { $lease=Open-LerNetOwnedProcessLease $item $ops } catch { $failed=$true }
    if ($null -ne $lease -or $failed -ne ($pinRace -eq 'denied') -or $state.Disposals -ne 1 -or $state.Stops -ne 0) {
        throw 'Handle-open exit/access race was classified incorrectly or leaked resources'
    }
}

foreach ($mismatch in @('time', 'path')) {
    $state = New-FixtureProcess $item.CreationDate $item.ExecutablePath
    if ($mismatch -eq 'time') { $state.Created=$state.Created.AddSeconds(1) }
    else { $state.Path='C:\Portable\LerNET.exe' }
    $lookup.Current=$state
    if ($null -ne (Open-LerNetOwnedProcessLease $item $ops) -or $state.Stops -ne 0 -or $state.Disposals -ne 1) {
        throw 'Reused PID identity was adopted or leaked its handle'
    }
}

$state = New-FixtureProcess $item.CreationDate.AddTicks(7) 'c:\fixtures\lernet\LerNET.exe'
$lookup.Current=$state; $lookup.Opens=0
$lease = Open-LerNetOwnedProcessLease $item $ops
if ($null -eq $lease) { throw 'CIM microsecond precision or case-insensitive path match rejected' }
$replacement = New-FixtureProcess $item.CreationDate.AddSeconds(1) 'C:\Unrelated\other.exe'
$lookup.Current=$replacement # Numeric PID now resolves elsewhere; actions must use the lease.
Stop-LerNetProcessLease $lease $ops
Confirm-LerNetProcessLeaseExit $lease $ops
& $ops.Dispose $lease.Process
if ($lookup.Opens -ne 1 -or $state.Stops -ne 1 -or $replacement.Stops -ne 0 -or $state.Disposals -ne 1) {
    throw 'Stop reopened the PID or failed to use/dispose the original lease'
}

$state = New-FixtureProcess $item.CreationDate $item.ExecutablePath
$lookup.Current=$state
$lease = Open-LerNetOwnedProcessLease $item $ops
$state.Exited=$true
Stop-LerNetProcessLease $lease $ops
Confirm-LerNetProcessLeaseExit $lease $ops
if ($state.Stops -ne 0) { throw 'Already exited process received a stop' }
& $ops.Dispose $lease.Process

$state = New-FixtureProcess $item.CreationDate $item.ExecutablePath
$lookup.Current=$state
$lease = Open-LerNetOwnedProcessLease $item $ops
$state.ExitDuringStop=$true
Stop-LerNetProcessLease $lease $ops
Confirm-LerNetProcessLeaseExit $lease $ops
& $ops.Dispose $lease.Process

$state = New-FixtureProcess $item.CreationDate $item.ExecutablePath
$lookup.Current=$state
$lease = Open-LerNetOwnedProcessLease $item $ops
$state.DenyStop=$true
$denied=$false
try { Stop-LerNetProcessLease $lease $ops } catch {
    $denied=$true
    $failureMetadata = Get-LerNetPreparationFailure 'stop-owned-process' $_ $false | ConvertTo-Json -Compress
    if (-not $failureMetadata.Contains('FixtureStopAccessDenied')) { throw 'Real stop failure lost diagnostic identity' }
}
if (-not $denied -or $state.Exited) { throw 'Live process access failure was reported as success' }
$stillLive=$false
try { Confirm-LerNetProcessLeaseExit $lease $ops } catch { $stillLive=$true }
if (-not $stillLive) { throw 'Unconfirmed live process was reported as stopped' }
& $ops.Dispose $lease.Process

$lookup.DenyOpen=$true
$denied=$false
try { Open-LerNetOwnedProcessLease $item $ops } catch { $denied=$true }
if (-not $denied) { throw 'Open access error was confused with a disappeared process' }
Write-Output 'Installer ownership and retained-handle policy fixtures passed; no real processes or registry queried.'
