# Android Expert: HTTPS health failure, build 120

## Report

After publishing v1.1.0 (Android build 119), the phone shows `https_probe_failed`,
default target folder, and the user reports that about half of sites are unavailable.
The diagnostic export was received and examined locally (about 99 MB).
It does not contain native Expert exit-health snapshots or the displayed
`https_probe_failed`, so it does not establish the specific TLS/DNS/socket
failure behind the screenshot. Raw logs contain private connection/configuration
data and are not copied into the repository or release artifacts.

## Observed phone evidence (Moscow time, October 8)

- Expert starts at 14:39:28 and is stopped at 14:41:32. Startup notes confirm
  system DNS for direct branches and exit DNS for protected branches.
- Ordinary VPN already reports failed health checks at 14:39:11 and 14:39:18,
  plus connection timeouts around 14:39:14–15, before Expert startup.
- Ordinary VPN again records a probe timeout at 14:43:14, after Expert stops.
- DNS NXDOMAIN responses occur for some destinations in the ordinary VPN log.
  They are evidence of failed name resolution, not proof of which component
  originated the response. No DNS setting is silently substituted by this fix.
- The shown underlay remains cellular; this export does not prove underlay loss.

## Confirmed code defects

1. `probeGate` created a bare Go HTTP transport, ignoring the generation's
   `adapter.CertificateStore` and time source. Pinned sing-box-lx's regular
   `common/urltest` and TLS clients use `adapter.RootPoolFromContext` and
   `ntp.TimeFuncFromContext`. Its Android certificate store obtains system roots
   through Android KeyStore/JNI. A manual probe also carries a session/caller
   context, so the physical gate's generation must supply the trust context.
2. A certificate rejection, DNS lookup error or HTTP 403/5xx was eligible for
   physical provider recovery. Recovery closes all handles of that exit; it
   cannot repair the probe endpoint's certificate or HTTP response. Both folder
   selection and background folder/standalone checks had this behavior.
3. Android displayed the opaque generic native code. Native probing discarded
   the typed error category, preventing diagnosis of certificate vs DNS vs timeout.

## Changes

- Use generation trust/time in the HTTPS probe; retain certificate verification.
- Do not restart transports on probe certificate/DNS/HTTP-status failures.
  Such results still fail health/selection; they are never called healthy.
- Return finite certificate/DNS/timeout categories, without raw provider errors
  or credentials. Explain them in shared Android/Windows status text.
- Export bounded native health transitions: running/revision/network epoch,
  underlay availability, aggregate exit phases/reason codes and selected-folder
  count. Omit destinations, profile identifiers, credentials and traffic bodies;
  unchanged polls do not log, changes are limited to one line per ten seconds.
- Do not change DNS policy, routing rules, fail-closed behavior, or direct fallback.

## Verification

Local Go Expert regression tests passed, including generation certificate trust,
rejection of untrusted certificates, non-destructive HTTP/certificate failures
for standalone and folder probes, and finite error categories.
58 Go Expert tests and 82 targeted JVM tests passed. Android release compilation
and lint passed. Version 1.1.1/build 120 is an Android candidate, built with
`-PlernetVersion=1.1.1`; the previously published release is not replaced.
APK signature and 16 KB alignment passed; exact hashes and package evidence are
recorded in the candidate's ANDROID-BUILDINFO.json. No VM, emulator,
host TUN or live phone test. A passing local test does not establish that all
reported phone connectivity failures share this cause.

## Follow-up evidence

An updated phone diagnostic export immediately after failure and failed
destinations are needed if the issue remains in build 120. The
single HTTPS endpoint is still a selection dependency; endpoint-only reachability
failure is not proof of total Internet loss.
