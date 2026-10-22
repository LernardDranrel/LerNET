package app.lernet.engine.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCheckTest {
    @Test fun `running config is measured by one fresh request through the vpn`() {
        assertEquals(LinkCheck.Plan(LinkCheck.Server.LIVE, LinkCheck.FullPath.RUNNING_VPN), LinkCheck.plan(active = true, vpnRunning = true, tcp = true))
    }

    @Test fun `other configs go through the running vpn, udp ones straight, full path on switching`() {
        assertEquals(LinkCheck.Plan(LinkCheck.Server.THROUGH_VPN, LinkCheck.FullPath.ON_SWITCH), LinkCheck.plan(active = false, vpnRunning = true, tcp = true))
        assertEquals(LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.ON_SWITCH), LinkCheck.plan(active = false, vpnRunning = true, tcp = false))
    }

    @Test fun `with the vpn off only the server is reached, no second core`() {
        assertEquals(LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.ON_CONNECT), LinkCheck.plan(active = false, vpnRunning = false, tcp = true))
        assertEquals(LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.ON_CONNECT), LinkCheck.plan(active = true, vpnRunning = false, tcp = true))
        assertEquals("Проверим при подключении", LinkCheck.notMeasured(LinkCheck.FullPath.ON_CONNECT))
    }

    @org.junit.Test fun fullIntensityPlansDiagnosticWhenVpnOff() {
        assertEquals(
            LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.DIAGNOSTIC),
            LinkCheck.plan(active = false, vpnRunning = false, tcp = true, intensity = LinkCheck.Intensity.FULL),
        )
        assertEquals(null, LinkCheck.notMeasured(LinkCheck.FullPath.DIAGNOSTIC))
    }
}
