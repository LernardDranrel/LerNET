package app.lernet.engine.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCheckTest {
    @Test fun `running config is measured by one fresh request through the vpn`() {
        assertEquals(LinkCheck.Plan(LinkCheck.Server.LIVE, LinkCheck.FullPath.RUNNING_VPN), LinkCheck.plan(active = true, vpnRunning = true, tcp = true))
        // Full press on the active config still uses the live tunnel — not a second core.
        assertEquals(
            LinkCheck.Plan(LinkCheck.Server.LIVE, LinkCheck.FullPath.RUNNING_VPN),
            LinkCheck.plan(active = true, vpnRunning = true, tcp = true, intensity = LinkCheck.Intensity.FULL),
        )
    }

    @Test fun `other configs with vpn on go straight to the server, full path on switching`() {
        // Through the live tunnel would only prove the active path — meaningless for a backup.
        assertEquals(LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.ON_SWITCH), LinkCheck.plan(active = false, vpnRunning = true, tcp = true))
        assertEquals(LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.ON_SWITCH), LinkCheck.plan(active = false, vpnRunning = true, tcp = false))
        assertEquals("Проверим при переключении", LinkCheck.notMeasured(LinkCheck.FullPath.ON_SWITCH))
    }

    @Test fun `with the vpn off only the server is reached, no second core`() {
        assertEquals(LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.ON_CONNECT), LinkCheck.plan(active = false, vpnRunning = false, tcp = true))
        assertEquals(LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.ON_CONNECT), LinkCheck.plan(active = true, vpnRunning = false, tcp = true))
        assertEquals("Проверим при подключении", LinkCheck.notMeasured(LinkCheck.FullPath.ON_CONNECT))
    }

    @Test fun fullIntensityPlansDiagnosticForNonActiveEvenWithVpnOn() {
        assertEquals(
            LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.DIAGNOSTIC),
            LinkCheck.plan(active = false, vpnRunning = false, tcp = true, intensity = LinkCheck.Intensity.FULL),
        )
        assertEquals(
            LinkCheck.Plan(LinkCheck.Server.DIRECT, LinkCheck.FullPath.DIAGNOSTIC),
            LinkCheck.plan(active = false, vpnRunning = true, tcp = true, intensity = LinkCheck.Intensity.FULL),
        )
        assertEquals(null, LinkCheck.notMeasured(LinkCheck.FullPath.DIAGNOSTIC))
        assertEquals("полная проверка · отдельное рукопожатие", LinkCheck.fullPathHint(LinkCheck.FullPath.DIAGNOSTIC, LinkCheck.Intensity.FULL))
    }
}
