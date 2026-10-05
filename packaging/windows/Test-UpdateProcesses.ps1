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
Write-Output 'Installer ownership fixture checks passed; no real processes or registry queried.'
