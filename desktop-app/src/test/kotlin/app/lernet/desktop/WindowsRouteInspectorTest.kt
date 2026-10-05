package app.lernet.desktop

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.NetworkInterface
import org.junit.Assume.assumeTrue
import org.junit.Test

class WindowsRouteInspectorTest {
    private val ipv4 = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
    private val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })

    @Test
    fun pointToPointIpv4PeerIsChecked() {
        val address = InetAddress.getByAddress(byteArrayOf(10, 1, 0, 1))
        val sample = WindowsRouteInspector.connectedSample(address, 31, setOf(address.address.toList()))
        assertThat(sample?.hostAddress).isEqualTo("10.1.0.0")
    }

    @Test
    fun uniqueLocalIpv6PeerIsCheckedWithoutDns() {
        val address = InetAddress.getByAddress(ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 })
        val sample = WindowsRouteInspector.connectedSample(address, 64, setOf(address.address.toList()))
        assertThat(sample?.address?.last()).isEqualTo(2.toByte())
        assertThat(sample?.address?.first()).isEqualTo(0xfd.toByte())
        assertThat(WindowsRouteInspector.connectedSample(address, 127, setOf(address.address.toList()))?.address?.last())
            .isEqualTo(0.toByte())
        assertThat(WindowsRouteInspector.connectedSample(address, 128, emptySet())).isNull()
    }

    @Test
    fun identicalAliasDoesNotApproveAnotherExpertInterface() {
        val result = WindowsRouteInspector.inspectExpertRoutes(listOf(ipv4), 42, 100L, { 7 }, { 100L }, { "LerNET" })
        assertThat(result).contains("1.1.1.1")
    }

    @Test
    fun recycledInterfaceIndexCannotApproveAChangedLuid() {
        val result = WindowsRouteInspector.inspectExpertRoutes(listOf(ipv4), 42, 100L, { 42 }, { 101L }, { "LerNET" })
        assertThat(result).isNotNull()
    }

    @Test
    fun expertCheckIncludesIpv6DespiteCorrectIpv4() {
        val result = WindowsRouteInspector.inspectExpertRoutes(listOf(ipv4, ipv6), 42, 100L,
            { if (it == ipv4) 42 else 7 }, { 100L }, { "Foreign VPN" })
        assertThat(result).contains(ipv6.hostAddress)
    }

    @Test
    fun nativeAliasReaderAcceptsARealWindowsInterfaceIndex() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        // Metadata only: no packets, routes, adapters or running cores are changed.
        val loopback = NetworkInterface.getByInetAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        assertThat(loopback).isNotNull()
        assertThat(WindowsRouteInspector.interfaceAlias(loopback.index)).isNotEmpty()
    }

    @Test
    fun ourWindowsAliasPassesWithoutConsultingTheDriverDescription() {
        val result = WindowsRouteInspector.inspectRoutes(listOf(ipv4), { 42 }, { index ->
            assertThat(index).isEqualTo(42)
            "LerNET"
        })
        assertThat(result).isNull()
    }

    @Test
    fun genericSingBoxAndForeignAliasesStillReportAConflict() {
        for (alias in listOf("sing-tun Tunnel", "TampleVPN", "Ethernet", "LerNET Other")) {
            val result = WindowsRouteInspector.inspectRoutes(listOf(ipv4), { 42 }, { alias })
            assertThat(result).contains(alias)
            assertThat(result).contains("1.1.1.1")
        }
    }

    @Test
    fun ipv6BypassIsNotHiddenByAValidIpv4Route() {
        val result = WindowsRouteInspector.inspectRoutes(
            listOf(ipv4, ipv6),
            { if (it == ipv4) 42 else 7 },
            { if (it == 42) "LerNET" else "Ethernet" },
        )
        assertThat(result).contains("Ethernet")
        assertThat(result).doesNotContain("1.1.1.1")
    }

    @Test
    fun aliasReadFailureCannotApproveTheRoute() {
        val failure = runCatching {
            WindowsRouteInspector.inspectRoutes(listOf(ipv4), { 42 }, { error("Win32: 87") })
        }.exceptionOrNull()
        assertThat(failure?.message).isEqualTo("Win32: 87")
        assertThat(WindowsRouteInspector.isLerNetInterface(null)).isFalse()
        assertThat(WindowsRouteInspector.isLerNetInterface("")).isFalse()
    }
}
