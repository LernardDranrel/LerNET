package app.lernet.vpn

import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import app.lernet.R
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.net.CidrPrefix
import app.lernet.engine.net.OpenTunInput
import app.lernet.engine.net.PlannedTun
import app.lernet.engine.net.TunRoutePlan
import app.lernet.engine.net.VpnGuard
import app.lernet.engine.redact.LerNetLog
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import java.net.InetAddress

/**
 * SFA `VPNService.openTun` and LxBox `BoxVpnService.openTun`:
 * called only from [LibboxPlatform.openTun]. Addresses always;
 * routes/DNS/packages only if `options.autoRoute`. API 33 uses
 * `inet4RouteAddress` else `0.0.0.0/0`; pre-33 uses `inet4RouteRange`.
 */
object TunEstablisher {
    private const val TAG = "LerNet.Tun"

    fun establish(
        service: VpnService,
        options: TunOptions,
        packageName: String,
        captureAllApplications: Boolean = false,
    ): ParcelFileDescriptor {
        CrashTrail.mark("TunEstablisher.establish")
        if (VpnService.prepare(service) != null) {
            throw Exception("android: missing vpn permission")
        }
        CrashTrail.mark("openTun snapshot begin autoRoute=${options.autoRoute} mtu=${options.mtu}")
        val snapshot = snapshot(options)
        if (captureAllApplications) {
            require(snapshot.autoRoute) { "Expert TUN must capture the device routes" }
            require(snapshot.includePackages.isEmpty() && snapshot.excludePackages.isEmpty()) {
                "Expert application rules belong in the policy, not Android bypass lists"
            }
            require(snapshot.inet4RouteExclude.isEmpty() && snapshot.inet6RouteExclude.isEmpty()) {
                "Expert TUN must not expose routes outside the policy"
            }
        }
        CrashTrail.mark(
            "openTun snapshot done autoRoute=${snapshot.autoRoute} dnsMode=${snapshot.dnsMode} " +
                "addrs=${snapshot.inet4Address.size}+${snapshot.inet6Address.size} " +
                "route4=${snapshot.inet4RouteAddress.size} range4=${snapshot.inet4RouteRange.size} " +
                "dns=${snapshot.dnsServers.size}",
        )
        dump(snapshot, options)
        val planned = TunRoutePlan.plan(
            OpenTunInput(
                autoRoute = snapshot.autoRoute,
                api33 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                dnsMode = snapshot.dnsMode,
                inet4Address = snapshot.inet4Address,
                inet6Address = snapshot.inet6Address,
                inet4RouteAddress = snapshot.inet4RouteAddress,
                inet6RouteAddress = snapshot.inet6RouteAddress,
                inet4RouteRange = snapshot.inet4RouteRange,
                inet6RouteRange = snapshot.inet6RouteRange,
                inet4RouteExclude = snapshot.inet4RouteExclude,
                inet6RouteExclude = snapshot.inet6RouteExclude,
                dnsServers = snapshot.dnsServers,
            ),
        )
        planned.notes.forEach { LerNetLog.w(TAG, it) }
        return tryEstablish(service, options, packageName, snapshot, planned, captureAllApplications)
    }

    private fun tryEstablish(
        service: VpnService,
        options: TunOptions,
        packageName: String,
        snapshot: TunSnapshot,
        planned: PlannedTun,
        captureAllApplications: Boolean,
    ): ParcelFileDescriptor {
        val mtu = options.mtu.takeIf { it > 0 } ?: snapshot.mtu
        val builder = service.Builder()
            .setSession(service.getString(R.string.app_name))
            .setMtu(mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        planned.addresses.forEach { prefix ->
            builder.addAddress(prefix.address, prefix.prefix)
            LerNetLog.i(TAG, "Builder.addAddress ${prefix.label()}")
        }
        if (snapshot.autoRoute) {
            planned.routes.forEach { prefix ->
                builder.addRoute(prefix.address, prefix.prefix)
                LerNetLog.i(TAG, "Builder.addRoute ${prefix.label()}")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                planned.excludes.forEach { prefix ->
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address), prefix.prefix))
                    LerNetLog.i(TAG, "Builder.excludeRoute ${prefix.label()}")
                }
            }
            planned.dnsServers.forEach { server ->
                builder.addDnsServer(server)
                LerNetLog.i(TAG, "Builder.addDnsServer $server")
            }
            snapshot.includePackages.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { LerNetLog.w(TAG, "addAllowedApplication failed: $pkg ${it.message}", it) }
                    .onSuccess { LerNetLog.i(TAG, "Builder.addAllowedApplication $pkg") }
            }
            val excluded = snapshot.excludePackages.toMutableSet()
            excluded.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { LerNetLog.w(TAG, "addDisallowedApplication failed: $pkg ${it.message}", it) }
                    .onSuccess { LerNetLog.i(TAG, "Builder.addDisallowedApplication $pkg") }
            }
            if (!captureAllApplications && snapshot.includePackages.isEmpty() && packageName !in excluded) {
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { LerNetLog.w(TAG, "addDisallowedApplication failed: ${it.message}", it) }
                    .onSuccess { LerNetLog.i(TAG, "Builder.addDisallowedApplication $packageName") }
            }
        } else {
            LerNetLog.w(TAG, "autoRoute=false — not installing default routes (SFA same)")
        }
        if (snapshot.httpProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val server = snapshot.httpProxyServer
            if (!server.isNullOrBlank()) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        server,
                        snapshot.httpProxyPort,
                        snapshot.httpProxyBypass,
                    ),
                )
                LerNetLog.i(TAG, "Builder.setHttpProxy $server:${snapshot.httpProxyPort}")
            }
        }
        LerNetLog.i(
            TAG,
            "establish autoRoute=${snapshot.autoRoute} mtu=$mtu " +
                "addrs=${planned.addresses.joinToString { it.label() }} " +
                "routes=${planned.routes.joinToString { it.label() }} " +
                "dns=${planned.dnsServers.joinToString()}",
        )
        CrashTrail.mark("before Builder.establish autoRoute=${snapshot.autoRoute} mtu=$mtu")
        val pfd = try {
            builder.establish()
        } catch (error: Throwable) {
            CrashTrail.recordFailure("Builder.establish", error)
            LerNetLog.e(TAG, "Builder.establish failed: ${error.message}", error)
            throw Exception("Builder.establish: ${error.message}", error)
        }
        CrashTrail.mark("after Builder.establish fd=${pfd?.fd ?: -1}")
        if (pfd == null) {
            CrashTrail.mark(VpnGuard.crumbEstablishFailed())
            LerNetLog.e(TAG, VpnGuard.ESTABLISH_NULL)
            VpnRuntime.signalRevoked(VpnGuard.crumbEstablishFailed())
        }
        return VpnGuard.requireEstablished(pfd)
    }

    private data class TunSnapshot(
        val autoRoute: Boolean,
        val dnsMode: String,
        val mtu: Int,
        val inet4Address: List<CidrPrefix>,
        val inet6Address: List<CidrPrefix>,
        val inet4RouteAddress: List<CidrPrefix>,
        val inet6RouteAddress: List<CidrPrefix>,
        val inet4RouteRange: List<CidrPrefix>,
        val inet6RouteRange: List<CidrPrefix>,
        val inet4RouteExclude: List<CidrPrefix>,
        val inet6RouteExclude: List<CidrPrefix>,
        val dnsServers: List<String>,
        val includePackages: List<String>,
        val excludePackages: List<String>,
        val httpProxyEnabled: Boolean,
        val httpProxyServer: String?,
        val httpProxyPort: Int,
        val httpProxyBypass: List<String>,
    )

    private fun snapshot(options: TunOptions): TunSnapshot {
        val dnsMode = snapField("dnsMode") {
            runCatching { options.dnsMode.value }.getOrDefault("")
        }
        return TunSnapshot(
            autoRoute = options.autoRoute,
            dnsMode = dnsMode,
            mtu = options.mtu,
            inet4Address = snapField("inet4Address") { collect(options.inet4Address) },
            inet6Address = snapField("inet6Address") { collect(options.inet6Address) },
            inet4RouteAddress = snapField("inet4RouteAddress") { collect(options.inet4RouteAddress) },
            inet6RouteAddress = snapField("inet6RouteAddress") { collect(options.inet6RouteAddress) },
            inet4RouteRange = snapField("inet4RouteRange") { collect(options.inet4RouteRange) },
            inet6RouteRange = snapField("inet6RouteRange") { collect(options.inet6RouteRange) },
            inet4RouteExclude = snapField("inet4RouteExclude") { collect(options.inet4RouteExcludeAddress) },
            inet6RouteExclude = snapField("inet6RouteExclude") { collect(options.inet6RouteExcludeAddress) },
            dnsServers = snapField("dnsServerAddress") {
                runCatching { collectStrings(options.dnsServerAddress) }
                    .onFailure { LerNetLog.w(TAG, "tun dns from libbox: ${it.message}", it) }
                    .getOrDefault(emptyList())
            },
            includePackages = snapField("includePackage") { collectStrings(options.includePackage) },
            excludePackages = snapField("excludePackage") { collectStrings(options.excludePackage) },
            httpProxyEnabled = false,
            httpProxyServer = null,
            httpProxyPort = 0,
            httpProxyBypass = emptyList(),
        ).let { base ->
            val enabled = snapField("httpProxyEnabled") { options.isHTTPProxyEnabled }
            if (!enabled) {
                CrashTrail.mark("openTun snapshot skip httpProxy fields enabled=false")
                return@let base
            }
            base.copy(
                httpProxyEnabled = true,
                httpProxyServer = snapField("httpProxyServer") { options.httpProxyServer },
                httpProxyPort = snapField("httpProxyPort") { options.httpProxyServerPort },
                httpProxyBypass = snapField("httpProxyBypass") { collectStrings(options.httpProxyBypassDomain) },
            )
        }
    }

    private inline fun <T> snapField(label: String, block: () -> T): T {
        CrashTrail.mark("openTun snapshot before $label")
        return try {
            val value = block()
            CrashTrail.mark("openTun snapshot after $label")
            value
        } catch (error: Throwable) {
            CrashTrail.recordFailure("openTun snapshot $label", error)
            throw error
        }
    }

    private fun dump(snapshot: TunSnapshot, options: TunOptions) {
        LerNetLog.i(
            TAG,
            "TunOptions dump autoRoute=${snapshot.autoRoute} strict=${options.strictRoute} " +
                "mtu=${snapshot.mtu} dnsMode=${snapshot.dnsMode.ifBlank { "?" }} " +
                "inet4=${snapshot.inet4Address.joinToString { it.label() }} " +
                "inet6=${snapshot.inet6Address.joinToString { it.label() }} " +
                "route4=${snapshot.inet4RouteAddress.joinToString { it.label() }} " +
                "route6=${snapshot.inet6RouteAddress.joinToString { it.label() }} " +
                "range4=${snapshot.inet4RouteRange.joinToString { it.label() }} " +
                "range6=${snapshot.inet6RouteRange.joinToString { it.label() }} " +
                "exclude4=${snapshot.inet4RouteExclude.joinToString { it.label() }} " +
                "exclude6=${snapshot.inet6RouteExclude.joinToString { it.label() }} " +
                "dns=${snapshot.dnsServers.joinToString()} " +
                "includePkg=${snapshot.includePackages.joinToString()} " +
                "excludePkg=${snapshot.excludePackages.joinToString()} " +
                "httpProxy=${snapshot.httpProxyEnabled}",
        )
        if (snapshot.dnsMode == Libbox.DNSModeDisabled) {
            LerNetLog.w(TAG, "dnsMode=disabled — SFA would skip addDnsServer")
        }
    }

    private fun collect(iterator: RoutePrefixIterator): List<CidrPrefix> {
        val out = mutableListOf<CidrPrefix>()
        while (iterator.hasNext()) {
            val prefix = iterator.next()
            out += CidrPrefix(prefix.address(), prefix.prefix())
        }
        return out
    }

    private fun collectStrings(iterator: StringIterator): List<String> {
        val out = mutableListOf<String>()
        while (iterator.hasNext()) {
            val value = iterator.next()
            if (!value.isNullOrBlank()) out += value
        }
        return out
    }
}
