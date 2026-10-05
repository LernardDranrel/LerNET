# LerNET persistent network engine

The native overlay extends a pinned sing-box-lx release. It owns one ingress
TUN and publishes independent immutable policy generations. It does not use
`StartOrReloadService` for Expert policy changes.

## Sources and build inputs

| Input | Pinned version |
|---|---|
| [sing-box-lx](https://github.com/Leadaxe/sing-box-lx) | `v1.14.1-lx.8`, commit `8e12ec7db6a77130fcc63b7c545d3a41f895f30a` |
| sing-tun submodule | `6f13ebcc131c622e3d98903de181ed21e0386fbe` |
| gVisor submodule | `117243aa02fa2915cb53d1d4549bfca3a3ebf238` |
| uTLS submodule | `59e89bb121d8cc7a0a1c87a930864122b5685d00` |
| WireGuard submodule | `6383749977b5a9741519540f9d40118bb73045b8` |
| [Go](https://go.dev/dl/) | `1.27.1`, Windows amd64 ZIP SHA256 `a3911b5e0e1b1053f25ed0675f4c1c6aad1e2bfcf253df2b9be4caabd2edd95d` |
| sagernet gomobile/gobind | `v0.1.13` |
| Android NDK | `30.0.16248370` |
| JDK | `21.0.12.1+1` |

The source checkout, portable tools, temporary files and build caches live in
ignored `build/v1.1.0-native`. No system Go/NDK installation is required.
The Android SDK is the workspace `android-sdk`; JDK is
`jdk/jdk-21.0.12.1+1`. The Windows core uses its protected sibling
`libcronet.dll`; its DLL search excludes the user PATH and current directory.
The host verifies the core and DLL hashes before elevating it.

## Reproduce the artifacts

From the repository root, prepare these workspace inputs:

```powershell
git clone --no-checkout https://github.com/Leadaxe/sing-box-lx.git build/v1.1.0-native/sing-box-lx
git -C build/v1.1.0-native/sing-box-lx checkout 8e12ec7db6a77130fcc63b7c545d3a41f895f30a
git -C build/v1.1.0-native/sing-box-lx submodule update --init submodules/gvisor submodules/sing-tun submodules/utls submodules/wireguard-go
```

Download `https://go.dev/dl/go1.27.1.windows-amd64.zip`, verify the SHA256 above,
and extract its `go` directory into `build/v1.1.0-native/toolchain`. Set
`GOROOT` to this portable directory, `GOPATH` to
`build/v1.1.0-native/gopath`, and put their `bin` directories in the current
process PATH. Install the build commands into that GOPATH:

```powershell
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
python tools/native/build-expert.py both
```

The script verifies the upstream commit, applies only explicit checked patch
anchors and tracked overlay files, and caps Go build concurrency at two workers.
It also builds the standalone Windows protection service from
`native/windows-protection`. On Windows, pinned gomobile's NDK `.cmd` wrappers
exceed the shell command-line limit while linking libbox. The build script makes
an isolated copy of gomobile and uses `clang.exe --target=...` directly; it never
edits the module cache. Build results and SHA256 sidecars are:

```text
build/v1.1.0-native/lernet-core.exe
build/v1.1.0-native/lernet-protection-service.exe
build/v1.1.0-native/libbox-expert.aar  (arm64-v8a + x86_64, Android API 24)
```

Building these files does not start a VPN, install a service or change routes.

## Android Simple health probes

The pinned libbox `NewStandaloneCommandClient` has no stream handler. Its unary
`URLTestOutbound` and `GetURLViaOutbound` calls establish and close their own RPC
connection through `getClientForCall` / `callWithResult`. Do not call `Connect`
on that client: `Connect` is the handler-bound streaming API and dereferences
the missing handler. The Kotlin probe retains final `Disconnect` for context
cleanup and still requires an actual successful L7 result. This contract also
applies when verifying Simple recovery after failed Expert preparation.

## Runtime boundaries

* The ingress Box owns the TUN, interface monitor and network manager.
* Each generation owns its own router, DNS registry and exit gates, and borrows
  the ingress network manager without owning its lifecycle.
* Publication compares expected instance, actual interface identity and revision
  while holding the publication fence. A canceled preparation is not published.
* New flows acquire the current generation; existing allowed flows keep their
  old generation until close. New Block rules close matching tracked flows.
* Persistent hijacked TCP/UDP DNS connections are canceled and closed on each
  successful policy publication. A subsequent query acquires current resolver
  policy; failed publication leaves the old DNS connection working. Completion
  releases the generation exactly once after its actual read loop stops.
* A cold gate coalesces wakeups, holds first flows within a deadline, and sleeps
  only after no business flows/probes/startups remain. HTTPS probes do not wake a
  sleeping gate or reset its business idle timer.
* Recovery closes only the failed exit's real provider and handles, rebuilds the
  provider, and proves HTTPS again. Transport incarnation and network epoch
  fence late dials/probes and recovery reactions.
* Folder targets retain all candidates and select at runtime. Candidate checks
  are concurrent, with a bounded settle window after the first healthy result.
  A hung neighbor cannot consume the entire healthy flow's connection budget.
* Windows protocol and bootstrap sockets use a validated hardware underlay and
  a dynamic binding callback. Ordinary Direct chooses each destination's
  preexisting Windows route, excluding the owned TUN, by longest prefix and
  combined metric; GUID/index/alias are revalidated around binding. Explicit
  corporate bindings remain explicit. Connected LAN
  prefixes are captured by two more-specific child routes. Updating the physical
  network refreshes the owned TUN routes and health epoch without replacing it.
  A native snapshot watcher includes IP, DNS, gateway, MAC and default metrics.
  Operational remote route prefixes are also captured, including PPP/SSTP
  routes independent of local addresses. Exact host-route metric collisions
  fail closed; actual full prefix coverage and owned identity are rechecked.
  Loopback/local-host delivery and scoped link-local/multicast are outside
  network-ingress capture. Foreign route-table changes refresh the health epoch.
* Retired generations and physical providers cancel independent contexts.
  Failed cleanup retains ownership, blocks wake/replacement, and remains in
  structured revision-specific status. Canceled startup reports pending until
  the real worker closes its provider, never a manufactured drained success.
* Android uses the platform-protected underlay sockets and the native-owned
  duplicate of the borrowed TUN descriptor supplied by its VpnService. The
  service retains its original descriptor; libbox duplicates it once.
* Windows guardian decodes the x64 MIB_IF_ROW2 index at byte 8, GUID at
  byte 12 and alias at byte 28. GUID alignment is four bytes. Raw-row tests
  and real guest GetIfEntry2 bytes cover these offsets; adapter creator,
  owner and permit checks still require the exact actual interface identity.
* Typed Windows corporate exits carry adapter GUID, alias and interface index.
  Every socket verifies the adapter is still operational and has that identity,
  rejects the owned ingress, and binds to that exact adapter. Such exits never
  use the default physical underlay. Android retains them as unsupported rules.
  Their individual gate skips automatic public HTTPS health recovery: an
  intranet-only split tunnel need not reach the public probe endpoint. A folder
  still selects its candidates by its configured HTTPS probe. When grouping
  corporate profiles, that endpoint must be reachable through each intended
  candidate; a working intranet alone does not establish folder eligibility.
* The trusted Windows core exposes only the Expert TUN command and requires an
  elevated token before creating a control listener. Generic proxy commands are
  excluded from this executable.
* A retained owner process handle, validated against creation time, shuts the
  Windows core down when its desktop owner dies. Protected adapters belong to
  the SCM guardian's creator handle: native opens only verified metadata and
  revokes permits before cleanup, then releases the creator after cleanup. This
  prevents a permit for a recycled interface LUID surviving native termination.

## Resource bounds

The ingress router admits at most **512 flows globally**, across exits and
retained generations. The production gVisor TUN uses its local stack branch:
TCP receive/send defaults are 32 KiB and maxima are 128 KiB each. Thus 512
admitted TCP endpoints have a maximum of 128 MiB of these protocol payload
buffers combined; this is not a bound on total process RSS or SYN metadata.
The pinned TCP forwarder separately limits pending handshakes to 1024.

Expert UDP NAT is capped at 256 sessions. Each pending queue holds at most
32 datagrams and 256 KiB of payload, for at most 64 MiB of queued payload.
Overflow drops the new datagram and preserves retained FIFO order. Reads and
close release both byte accounting and actual pooled payload buffers.

## Verification

Run native tests from the **parent source module**, so its dependency versions
are used for the pinned sing-tun package:

```powershell
go test -p 2 -tags with_gvisor ./lernet/expert github.com/sagernet/sing-tun -run 'Test(Acceptance|LerNET|Connected)' -count=1
```

Tests exercise production gate/mux/queue code, 100 concurrent TCP/UDP callers,
deadlines, canceled startup, real provider replacement, late retired dials,
retained streaming contexts, stale health publication, actual stack buffer
options, UDP overflow/FIFO/drain, and connected subnet prefix splitting. Local
TLS test trust is confined to the test process.

Actual TUN, route capture, platform process attribution, crash protection and
Android lifecycle acceptance require isolated Windows/Android guests. Passing
the native tests or compiling the artifacts does not establish those OS-level
properties. Follow `docs/plans/v1.1.0-network-manager.md` and the acceptance
review evidence for the release gate.

See [control-protocol.md](control-protocol.md) for the shared native API.
