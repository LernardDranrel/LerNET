# Expert control protocol, version 1

Windows starts `lernet-core.exe expert --listen 127.0.0.1:0 --token-file PATH --owner-pid PID --owner-started-ms EPOCH_MS`.
PATH contains a random secret of at least 32 characters and has a private ACL.
The executable exposes only Expert and requires a Windows elevated token before
opening a control listener; generic upstream proxy commands are excluded.
The retained owner process handle is checked against its creation time before
startup. Owner death cancels control and forces process exit after a bounded
five-second cleanup window, even if an outbound refuses to stop.
The child binds its own port and prints exactly one readiness line:

```text
LERNET_CONTROL_READY {"port":12345,"protocol_version":1}
```

All HTTP calls use the loopback port with `Authorization: Bearer SECRET`, no
system proxy and no redirects. Requests with Origin or non-loopback peer IP are
rejected. Configuration and secrets are never passed on the process command
line. JSON is strictly decoded, limited to 22 MiB, and responses are no-store.

## Capabilities

`GET /lernet/v1/capabilities` returns strict booleans:

```json
{"protocol_version":1,"hot_policy_apply":true,"preserves_tun":true,"independent_exit_lifecycle":true,"atomic_prepare_commit":true,"bounded_first_flow_wait":true,"destination_redirect":true,"native_health_recovery":true,"platform_crash_guard":false}
```

OS crash protection is supplied by the platform, and is not implied by native
policy control. The caller must reject unsupported/missing capability keys.

## Operations

Every mutating/control operation is `POST /lernet/v1/OPERATION`.

| Operation | JSON fields |
|---|---|
| `start` | `revision`, `ingress` object, `policy` object, `exits` manifest object, optional `guarded_adapter` |
| `apply` | identity fence below, next `revision`, `policy`, `exits` |
| `stop` | identity fence |
| `wake`, `sleep`, `recover` | identity fence, physical exit `tag` |
| `probe` | identity fence, `tag`, HTTPS `url`, `timeout_ms` (1000–45000) |
| `network_changed` | identity fence, Windows actual physical `underlay_interface` |

The identity fence is `instance_id`, `interface_id`, `expected_revision`.
Start/apply return the actual acknowledgment:

```json
{"instance_id":"random native instance","interface_id":"actual-name:ifIndex:fd","revision":2,"if_index":9}
```

Windows verifies native ifIndex/LUID and actual capture routes before presenting
the session as running. Android also verifies its retained service descriptor
lease. Exit operations acquire the expected generation atomically with the
fence, so they cannot act on a successor published between check and use.

`GET /lernet/v1/status` returns that acknowledgment, `running`, current and
draining `exits`, selected `folders`, real `flows`, `network_epoch` and Windows
`underlay_interface`. An exit reports `phase`, separate `health`, factual
`latency_ms`, `last_check_ms`, `failures`, pending/active/draining counts and a
finite reason code. Provider construction does not mean healthy HTTPS.

`direct_families` optionally reports local `ipv4`/`ipv6` evidence using only
`available`, `limited`, `unavailable`, `unknown`, plus `source` and optional
underlay `interface`. Windows inspects routes outside the owned ingress;
Android reports source-address evidence and never upgrades it into a routing
or Internet reachability promise. The enclosing identity, revision and
`network_epoch` fence these facts. A missing/unknown family never means absent.
These facts do not describe remote proxy capability. AAAA may receive
NOERROR/NODATA only when IPv6 absence is known and every possible first matching
traffic rule for the queried name uses unprotected Direct. Mixed generations
can prove this for one name while preserving proxy IPv6 for another. Domain
predicates use native matchers; unknown IP/country/owner predicates remain unknown.
A possible protected, proxy, Block or redirect action prevents adaptation.
This runs in the shared DNS client after resolver selection, so both sync/async
and legacy/current DNS rule paths obey it. Rejection, predefined responses,
protected resolvers, response checkers and DNSSEC/signed queries are preserved.
Synthetic NODATA is not stored in the native DNS cache and family facts are
invalidated on network epoch changes. Application caches and bypassing DoH
remain outside this adaptation.
The transparent Direct compiler omits payload sniffing so a lazy TCP handshake
can be rejected before an external dial succeeds. History wrappers preserve
native handshake callbacks without bypassing byte accounting.

New finite errors distinguish `system_route_ipv4_unavailable`,
`system_route_ipv6_unavailable`, `system_route_manager_unavailable`,
`system_route_snapshot_failed`, `system_route_bind_failed`, `ingress_not_ready`
and `ingress_ready_timeout`. Startup flows are admitted within the existing
512-flow bound and wait at most five seconds for capture proof. Stop or failed
startup cancels them before ingress cleanup.

`close_confirmed` is true only after full ingress cleanup succeeded. A failed
close result is retained on repeated calls, including failed-Start cleanup;
`running:false` alone does not prove that the native TUN descriptor is drained.

Corporate direct exits use `lernet_interface` with adapter `guid`, `name` and
positive `index`. Canonical unbraced and Windows braced GUIDs name the same
adapter. Definitions remain loadable while disconnected; actual TCP/UDP sockets
verify operational status, exact identity and owned-ingress exclusion around
binding to the intended adapter. Public HTTPS background recovery is disabled
for corporate split tunnels, which may deliberately lack Internet access.

Android sets desktop `auto_detect_interface` false but its borrowed manager
still applies the actual `VpnService.protect` callback. The service supplies its
retained descriptor as a borrowed fd; libbox duplicates it once for native TUN.

`route.lernet_unknown_owner_dns_safe` admits Android system DNS only with a
validated prefix of unresolved-package protected DNS rules and an exact reject
catchall before ordinary DNS rules. Remote resolvers must detour through
manifest exits/folders. The exception never relaxes ordinary unknown-owner
TCP/UDP traffic or Windows process guards.

Flows contain monotonic numeric int64 `id` (positive, not a JSON string), `revision`, `started_ms`, original `source`,
`destination`, `network`, factual process/packages, actual `outbound`,
`node_ids`, `state`, `reason`, `closed`, upload/download byte counts. Denied
decisions contain no invented traffic. Ordinary direct flows become active on
proven positive transfer.

Additive observation fields preserve facts separately: `source_ip`, `source_port`,
`destination_ip`, `destination_port`, `domain`, `protocol` (sniffed application
protocol; `network` remains TCP/UDP), `process_name` (basename of the actual
process path), and `geo_country` only when native metadata already contains it.
`started_ms`, `updated_ms`, and factual `closed_ms` are Unix milliseconds.
Missing optional fields remain unknown in clients, including with older cores.
The legacy display `destination` may be a domain; it is never parsed back into
invented address/port facts. Flow IDs are scoped to `instance_id`.

`error_stage` is `dns`, `route`, `dial`, `transfer`, or `connection`; `error_reason` is
`timeout`, `name_not_found`, `resolution_failed`, or `network_error`. Typed DNS
errors and exact UDP DNS exchange failures report DNS; `net.OpError.Op` reports
dial/read/write stages. A generic failure retains the unknown connection stage.
Exact reviewed `system_route_unavailable`, `system_route_destination_invalid`,
`system_route_changed`, `interface_binding_invalid`, `interface_binding_unavailable`,
`interface_binding_identity_changed`, and `interface_binding_owned_ingress`
errors retain that finite reason and report the `route` stage.
EOF, local closed sockets, and cancellation are clean close observations. Raw
error strings never enter flow JSON. Kernel `close_reason` is separately
`finished`, `idle_timeout`, or `reset`; these are not proof of TLS/HTTP failure.
Encrypted application errors require the application's own evidence.

Status includes `flow_history_limit:500` and cumulative `flow_dropped_count`
for this native session. Completed records yield before active records. If all
slots are active, a new completed decision is omitted; a new live flow replaces
the oldest retained live flow. The counter makes either omission explicit.
Shared history likewise prefers current active records, scopes IDs by identity,
and marks a previously active row absent from a native snapshot as activity
unknown, with no invented close time. Clearing shared closed history suppresses
those retained native rows on later polls; it does not stop active connections.

An HTTP transport failure can occur after commit. The caller must reconcile the
actual revision with status; an unavailable acknowledgment is not evidence that
the previous policy is still active. Unreconcilable state requires stopping the
owned ingress and clearing the GUI's applied state. Provider errors cross the
control boundary as finite protocol codes, without raw credentials/hosts.

## Windows Direct and guarded adapter ownership

The global ordinary Direct outbound sets `lernet_system_route:true`. Each
destination is looked up in the current Windows route table after excluding
the actual owned TUN. Selection uses longest prefix, then route plus interface
metric. The chosen operational interface GUID/index/alias is validated again
after socket binding. A missing route fails closed; it never silently switches
to a guessed physical interface. Explicit profile/bootstrap and typed corporate
outbounds retain their separate binding contracts.

Windows captures every operational, non-owned remote unicast route, including
PPP/SSTP prefixes that are independent of an adapter's /32 address. Broader
prefixes receive two more-specific child routes. Exact /32 and /128 routes
require a strictly better metric: owned route and interface metrics are read
back as zero, and a foreign zero-metric host collision rejects activation.
Full prefix coverage is proved against the actual route table before the
running acknowledgment and after updates. No foreign route is deleted.
Loopback, addresses assigned to this device, multicast and scoped link-local
delivery are outside this network-ingress capture guarantee. Global Direct
still preserves Windows loopback paths when explicitly redirected locally.

The two-second native watcher validates the actual owned GUID/LUID/index and
operational status even when the physical fingerprint is unchanged. Route-only
changes on foreign virtual adapters invalidate health and refresh capture.
Loss of identity or unproved capture closes ingress, sets running:false and
records finite stop_reason; it cannot remain a falsely connected TUN.

Status includes retired_cleanup_failures entries {revision,reason,exit_tags}.
The finite reasons are expert_cleanup_pending, exit_stop_failed, or
generation_stop_failed. Current exits with the same tag retain their own
independent status. Every generation and provider has a separately canceled
context. A provider whose Close failed stays owned and cannot wake or be
replaced. Pending canceled startup workers release first flows immediately,
but close_confirmed remains false until those actual providers finish cleanup.
cleanup_reason describes unresolved session cleanup, without provider text.
Further prepare/publication is rejected with generation_cleanup_unconfirmed
while a retired zero-flow generation or current provider has unproved cleanup;
normal generations draining established flows remain allowed. A failed Start
keeps the control session owned until cleanup is confirmed. Matching identity
Stop retries can reconcile a canceled startup worker's genuine late drain.
Persistent hijacked TCP/UDP DNS ingress sessions close after a successful
publication, preventing further queries through retired DNS policy. Cancellation
is inside the publication fence; actual connections close outside its lock.
Late old-generation registration is rejected. Failed publication leaves DNS
sessions untouched. close_confirmed also waits for their actual read loops.
Business TCP/UDP flows arriving during idle close wait within the same original
first-flow budget, count toward bounded pending admission, then share one wake.
Health probes neither wait nor wake. Session close and network epoch changes
cancel stale waiters; a provider Close failure never constructs a replacement.

Top-level manifest health settings are persisted per policy:
`health:{probe_min_interval_ms,probe_max_interval_ms,active_probe_timeout_ms,failed_checks_before_recovery}`.
Intervals must be 1000–60000 ms with maximum at least minimum, active timeout
1000–15000 ms, and failure threshold 1–10. Omitted settings use 3000–7000 ms,
4000 ms, and 2 failures.
Every physical exit uses its generation's immutable schedule; folders inherit
omitted health fields while retaining explicitly supplied folder fields.
Candidate/manual deadlines remain separate; a cold business first-flow timeout
cannot shorten a probe's chosen deadline. Invalid settings reject preparation
with `invalid_health_settings` and retain the current revision and schedule.
The active timeout bounds each HTTPS proof. Actual transport recovery also
has its separate startup budget; it is not bounded by the HTTPS timeout alone.

When crash protection is armed, the SCM guardian creates and retains the
Wintun creator handle before native start. `/start.guarded_adapter` carries its
numeric `pid`, `started_at_ms`, `luid`, `if_index` and string `tun_name`, `guid`.
Native verifies and opens that existing adapter; it never creates or deletes
the guarded device. An opened Wintun metadata handle does not pin its lifetime.

The admin-only `\\.\pipe\LerNETProtection.Control.v2` accepts one bounded JSON
line. Native sends `{operation,pid,started_at_ms}` and requires `{ok:true}`.
`revoke_owned_tun` removes permits while retaining the creator; native awaits
this acknowledgment before closing the TUN. Only after session, metadata and
other native cleanup finishes does `release_owned_tun` remove the creator.
On process death the guardian revokes first and releases second. Failure of
either acknowledgment prevents a false `close_confirmed:true`. Guard-off
sessions do not contact this pipe.

## Android JNI equivalent

The patched AAR exports `io.nekohasekai.libbox.Libbox`:

```text
expertCapabilities(): String
newExpertSession(ingressJSON: String, platform: PlatformInterface): ExpertSession
```

The session exports:

```text
start(revision: long, policyJSON: String, manifestJSON: String): String
apply(instance: String, interfaceID: String, expectedRevision: long,
      nextRevision: long, policyJSON: String, manifestJSON: String): String
status(): String
close(): void
networkChanged(): void
wakeExitAt(instance: String, interfaceID: String, revision: long, tag: String): void
sleepExitAt(instance: String, interfaceID: String, revision: long, tag: String): void
recoverExitAt(instance: String, interfaceID: String, revision: long, tag: String): void
probeExitAt(instance: String, interfaceID: String, revision: long,
            tag: String, url: String, timeoutMs: long): String
```

All returned JSON and capability semantics match HTTP. JNI errors are sanitized
protocol codes. Concurrent close cancels session operations. The legacy
unfenced exit methods exist for compatibility; LerNET uses the `At` methods.

## Policy manifest and metadata

Ingress contains exactly one gVisor TUN. Windows ingress requires
`route.default_interface` naming a hardware adapter, `auto_route:true` and no
route exclusions. Native adds global split defaults and capture routes for
connected operational adapter subnets, including corporate virtual adapters.
Policy generations cannot own inbounds, endpoints, listeners, services, NTP,
experimental servers, network namespaces or HTTP clients.

The manifest declares `direct_tag`, optional `probe_url`, physical `exits` and
optional runtime `folders`. Exit fields are `tag`, `mode` (`warm`/`cold`),
`idle_timeout_ms`, `first_flow_timeout_ms`, `startup_timeout_ms`,
`max_pending_flows`. Folder fields include all `candidate_tags`, optional
`preferred_tag`, `selection` (`preferred`/`fastest`), `auto_swap`,
`probe_timeout_ms`, `cooldown_ms`, `health_ttl_ms`, `max_pending_flows`,
`first_flow_timeout_ms`, `active_probe_timeout_ms`, `probe_min_interval_ms`,
`probe_max_interval_ms`, `failed_checks_before_recovery` and optional `probe_url`.

Terminal route actions retain `lernet_node_ids`, `lernet_protected`, and
`lernet_fallback` (`block`/`direct`). A protected action cannot request direct
fallback. Reject actions retain `lernet_node_ids`. `route.lernet_owner_guard`
(`package`/`process`) fails closed when required owner attribution is unknown.
Missing protected DNS context never grants direct fallback.

## Windows capture-update failure details

HTTP422 with `error: "expert_capture_route_update_failed"` may include
`route_update_stage` and `win32_code` (unsigned integer; zero means unavailable).
Stages are finite: configuration, previous_ranges, next_ranges, existing_metric,
read_route, add_route, read_obsolete_route, obsolete_metric, delete_route.
Raw syscall/provider text, profile secrets and route addresses are not exposed by
these fields. Existing clients may ignore them. Android does not perform this
Windows route-table update and its JNI contract is unchanged.

Expert ensures the next capture routes before removing only obsolete own capture
rows. It never flushes every route of its adapter during a network-change update;
address-created on-link rows remain intact. Final capture verification is required
before success. Failure still closes unproved ingress rather than claiming a live TUN.


## Bounded content observation (6 October 2026)

Each flow may expose optional `inspection`: `upload_prefix`/`download_prefix` are JSON base64
byte arrays (at most 512 decoded bytes each); `transfers` is a tail of at most eight successful
observations with numeric `sequence`, `at_ms`, `bytes` and boolean `upload`; `transfer_count`
is cumulative. `payload_available` distinguishes copied socket data from kernel counters.
TCP observations are stream I/O fragments, not packets or HTTP messages; UDP wrapper
observations are datagrams. Counter-only observations do not claim packet boundaries.
Snapshots deep-copy buffers. Prefix capture performs no extra reads or sniffing and cannot
alter forwarded traffic. Payload stays in session memory and authenticated loopback/JNI
status, not ordinary logs or profile/network-report exports. This is not TLS decryption,
complete body recording, or packet reconstruction. Existing readers may ignore these fields.

## Original-network DNS (6 October 2026)

Portable `NetworkPolicy.dns` defaults to `mode: SYSTEM`; `CUSTOM` accepts an explicit
IPv4 UDP server. This additive field defaults to SYSTEM when absent from old policies.
It is separate from the ordinary VPN's legacy direct DNS setting.

Expert generations use separate `dns-bootstrap` (endpoint resolution) and
`dns-direct` (ordinary Direct queries). Windows local transports set the overlay
option `lernet_preserve_destination:true`: the intercepted original DNS endpoint
is retained before destination matching clears it, and forwarded over a verified
non-owned route, retaining TCP/UDP and IPv6 interface scope. A failed original
resolver never switches to a public resolver. Missing/synthetic endpoints use
original-network discovery only for service lookups. Windows TUN `dns_mode` is
disabled to retain Windows DNS Client selection; DNS capture/rule evaluation
remains enabled. Windows generation-wide DNS caching is disabled to avoid mixing
answers from independently selected system resolvers; the OS cache is retained.

Explicit Windows CUSTOM remote DNS sets `lernet_system_route:true` on its
`RemoteDNSServerOptions`. This is a DNS transport option, distinct from the same
named Direct outbound option. A configured outbound detour continues to take
precedence over socket binding; protected DNS remains through its assigned exit.

Android local DNS uses the platform `LocalDNSTransport` bridge with an explicit
underlying non-VPN `Network`: `DnsResolver.rawQuery` on Android 10+, or bounded
A/AAAA lookups on supported Android 8–9. Private DNS changes participate in the
network fingerprint. The bridge never resolves against the process-wide active
VPN or substitutes a public resolver. Application-managed DoH is independent.
