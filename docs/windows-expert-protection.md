# Windows Expert protection

The native traffic core and the Windows crash guard have separate jobs. The core evaluates the routing tree. The guard keeps Windows from opening a route around a missing core/TUN.

The core verifies route capture before activation and rechecks the owned adapter and operational route table every two seconds. This polling is not an atomic veto of changes made by another VPN: without the system guard, a new more-specific foreign route can bypass the TUN before detection. Policy protection applies to traffic actually captured by the TUN. Enable the device-wide WFP guard when traffic must remain blocked through route changes or loss of capture. The guest acceptance includes a late foreign-route change while this guard is active.

## Ownership and lifetime

- WFP provider: `2dbb248a-91c0-45b4-96cc-dd0a1b4e2b8a`.
- WFP sublayer: `6fa5577e-591a-41aa-8d9c-bb0a16d8e4da`.
- Auto-start LocalSystem SCM service: `LerNETProtection`, dependent on BFE.
- The provider, sublayer, hard blocks and core/IPC/network-service permits are persistent. TUN permits are dynamic objects owned by the guardian service's WFP session; no stale interface permission is restored from disk.
- The service creates and retains the actual Wintun creator handle. The native core opens that exact adapter and verifies its name, GUID, LUID and interface index. An existing same-named adapter is never adopted. Wintun's metadata-only open handle is not used as an ownership pin.
- The service pins the native process handle, full protected image path and creation timestamp. Core exit revokes dynamic permits before closing the creator, preventing LUID recycling while those permits exist. Native orderly shutdown performs revoke, native session cleanup, then creator release.
- Persistent policy replacement is one WFP transaction. A failed replacement retains the previous persistent blocks; it may intentionally remove the old TUN allowance, leaving traffic blocked. It never substitutes a direct fallback.
- Closing the GUI or its management session does not delete persistent filters. The native core watches the exact GUI parent identity. Stopping the guardian revokes its TUN permits and stops its pinned native process before releasing the adapter; persistent blocks remain.
- Recovery enumerates Windows filters and removes only objects whose provider **and** sublayer match the two LerNET keys. Display names or a disk-local filter-id list are never used for ownership.
- Before removing base blocks, explicit recovery asks the service to confirm creator release. The service rejects a GUI/helper release while its pinned native process is still alive; a live core therefore cannot silently turn recovery into an underlay bypass.
- Explicit uninstall first shuts down the exact installed GUI and its owned descendants, then invokes `lernet-protection-service.exe --uninstall` to recover filters and stop/delete only the owned SCM service. The native helper never launches a GUI or TUN. The Inno `usUninstall` hook runs after confirmation, not on update or a cancelled prompt; a failed recovery aborts file removal. User data, foreign filters and services remain intact.

Provider-associated persistent filters are disabled on BFE startup if their provider has no associated auto-start service. The real SCM service supplies that association and owns the guarded adapter and dynamic permits. Startup does not recreate a stale TUN or remove persistent blocks. Readiness verifies both the configured content-addressed service image and the actual running process image.

The local control pipe is `\\.\pipe\LerNETProtection.Control.v2`, with a protected SYSTEM/Administrators ACL and remote access rejected. Bounded JSON commands prepare, arm, revoke and release one exact native lease. Status verifies the actual process, adapter GUID/index and kernel filter IDs, layer, conditions, weight, action and ownership. A communication error during WFP close is not treated as proof of removal: the creator is retained until a fresh WFP session independently reports every captured filter ID absent.

This is not a boot-time network driver. The interval before BFE starts during early Windows boot, or while BFE is deliberately unavailable, is outside the guarantee. Administrator-level firewall changes and third-party kernel drivers are also outside this protection model. Forced guardian-process death relies on Windows terminating its dynamic WFP session; user-mode code cannot promise the ordering of kernel cleanup. Actual crash and adapter reuse tests are required before release, and do not establish a guarantee against a hostile administrator.

## Scope and allowances

Whole-device mode requires an explicit user choice. Both IPv4 and IPv6 `ALE_AUTH_CONNECT` and `ALE_AUTH_RECV_ACCEPT` layers contain a hard block. Higher-weight soft permits in our own sublayer allow:

1. The actual outgoing TUN LUID at connect layers and arrival TUN LUID at receive layers. Permits exist only in the service's dynamic session for its retained creator and pinned native lease. Only fresh process and adapter validation can restore them after service/BFE/reboot.
2. Only `lernet-core.exe`, which owns underlay connections and enforces the graph, including allowed direct branches. A blanket Java/GUI exemption would be unsafe and is not used.
3. The GUI's actual EXE path, TCP, exact private control port and only `127.0.0.1`/`::1`. There is no general localhost exemption.
4. Windows `svchost.exe` for UDP DHCP client/server ports 68/67 and 546/547. Ordinary system DNS is not exempted.
5. IPv6 neighbor/router discovery, ICMPv6 types 133–136/code 0. There is no generic ICMP exemption.

These permits remain soft: another firewall can still block a connection. LerNET does not override another VPN's filtering policy.

Whole-device protection also blocks ordinary localhost connections between applications: these local sockets do not traverse the TUN. Only LerNET's exact control channel is allowed. A local proxy or companion application may therefore stop working until this additional protection is disabled. An externally managed corporate VPN is also subject to the block: its transport process is not the trusted LerNET core. A visible corporate adapter does not prove its underlay transport is still usable. Corporate coexistence must be checked separately with this optional guard disabled; protected graph branches still forbid direct fallback while the TUN is running.

Selective application protection requires full executable paths. It cannot promise that a shared Windows DNS service or a child process belongs to that application's flow. Domains, geo groups, negations, process names without a full path and mixed graph conditions cannot be turned into equivalent selective WFP rules. The UI must explain that limitation and offer explicit whole-device coverage, or refuse to claim crash protection.

## Core and service file integrity

WFP app identities identify a path, not a hash. Therefore a user-writable traffic-core path must not receive the unrestricted core permit.

`WindowsExpertProtection.prepareProtectedCore(source)` copies the verified core and its dependencies to a bundle SHA-256-addressed folder below the real Windows Program Files `LerNETProtection` folder. The folder and executable owner is Administrators, the DACL is protected, and SYSTEM/Administrators have full access. Ordinary Users can traverse/read the directories but have read-only access to the core EXE and its DLLs: they cannot execute a second copy at the exempt path to bypass the guard. The required `wintun.dll` and `libcronet.dll` siblings are also copied into that protected folder after comparison with the bundled resources; every DLL receives the same protected owner and read-only user DACL and is rechecked before arm. The bundle directory identity includes the core and both dependency hashes, so a DLL-only update can create a new immutable directory. The core hash must match `/runtime/lernet-core.exe.sha256`. The core must run elevated with that folder as its working directory and restricted native DLL lookup. Parent reparse points, executable user permissions and an unexpected file hash are rejected. `arm` verifies those properties before touching SCM/WFP. The Expert core also excludes the generic unprivileged proxy CLI.

The guard binary is installed using the same scheme. An existing same-named SCM service is adopted only when its quoted binary path belongs to the exact owned folder and its service type/account match. An unquoted command line, extra arguments, another folder or a different executable are rejected.

## Integration order

1. Confirm whole-device coverage and show its scope/limitations.
2. Prepare and use the protected core path. The running core's actual path must match the WFP exemption.
3. Allocate private loopback IPC and include the real GUI path/port.
4. Arm a fail-closed plan with no TUN allowance before protected traffic is admitted.
5. Ask the service to create an owned adapter for the exact native PID and creation timestamp. Pass the returned adapter identity in native `/start`; native opens it without taking creator ownership.
6. Read the real native interface ACK, match it to the prepared owner, install dynamic TUN permits through the service, and verify the complete active filter set.
7. On core/start/apply failure, retain the guard and show recovery. Do not recover from a `finally` block.
8. Stopping the Expert TUN retains the base guard and can leave ordinary Internet blocked. Only the separate explicit disable-protection/recovery action removes the guard's filters, after the native process is confirmed stopped.

If a content-addressed core path changes during an update, the new process path must be armed before restoring protected traffic. Persistent filters found at startup with no live TUN are recovery state, not a successful VPN connection.

Ordinary guardian control has one eight-second deadline per operation, including a status query followed by a mutation. One second is reserved for terminating and confirming the local IPC helper; its raw reply is capped at 16 KiB before decoding. Installation uses a separate SCM budget. A timed-out or oversized reply means the command result is unknown: killing the IPC helper cannot undo work already accepted by the service. Existing filters remain in place, and fresh readback is required to confirm the result.

The desktop's static WFP management session explicitly sets `FWPM_SESSION0.txnWaitTimeoutInMSec` to 2,000 ms. This bounds acquisition of a contended transaction lock, rather than inheriting BFE's 15-second default. It does not impose a two-second deadline on every WFP RPC or change the persistent filter lifetime.

## Verification performed

The task's laptop network is not changed during development. Protection tests use a fake WFP transaction store, pure plan/ACL/path tests and memory-only GUID/layout tests. The Go SCM service is compiled and its struct layout tested without any SCM API call. Real filter enforcement, DHCP renewal, foreign VPN interactions, process failure and reboot behavior require an isolated Windows VM/device test; unit tests cannot establish those network properties.

## Primary references

- [WFP object management and lifetimes](https://learn.microsoft.com/en-us/windows/win32/fwp/object-management).
- [FWPM_SESSION0 client fields and transaction contention timeout](https://learn.microsoft.com/en-us/windows/win32/api/fwpmtypes/ns-fwpmtypes-fwpm_session0).
- [Wintun creator versus open-handle lifecycle source](https://git.zx2c4.com/wintun/tree/api/adapter.c).
- [NET_LUID indexes can be freed and reused](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ndis/nf-ndis-ndisiffreenetluidindex).
- [FwpmEngineClose0 return codes and session termination](https://learn.microsoft.com/en-us/windows/win32/api/fwpmu/nf-fwpmu-fwpmengineclose0).
- [FwpmFilterGetById0 kernel identifier readback](https://learn.microsoft.com/en-us/windows/win32/api/fwpmu/nf-fwpmu-fwpmfiltergetbyid0).
- [FWPM_FILTER0 flags, persistence, service requirement](https://learn.microsoft.com/en-us/windows/win32/api/fwpmtypes/ns-fwpmtypes-fwpm_filter0).
- [Filtering conditions available at each layer](https://learn.microsoft.com/en-us/windows/win32/fwp/filtering-conditions-available-at-each-filtering-layer).
- [Filtering conditions, native types and LUID identity](https://learn.microsoft.com/en-us/windows/win32/fwp/filtering-condition-identifiers-).
- [FWP_VALUE0 pointer layout and host-order IP/port values](https://learn.microsoft.com/en-us/windows/win32/api/fwptypes/ns-fwptypes-fwp_value0).
- [WFP filter arbitration, soft permit and hard block](https://learn.microsoft.com/en-us/windows/win32/fwp/filter-arbitration).
- [FwpmGetAppIdFromFileName0 ownership and freeing](https://learn.microsoft.com/en-us/windows/win32/api/fwpmu/nf-fwpmu-fwpmgetappidfromfilename0).
- [WinSDK fwpmtypes.h](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/shared/fwpmtypes.h).
- [WinSDK fwpmu.h GUID definitions](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/um/fwpmu.h).
- [Inno Setup elevated temporary-directory and launch security](https://jrsoftware.org/is6help/topic_securitymeasures.htm).
