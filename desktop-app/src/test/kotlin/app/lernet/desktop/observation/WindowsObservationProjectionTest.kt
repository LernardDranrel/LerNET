package app.lernet.desktop.observation

import app.lernet.engine.net.observation.SourceState
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs only fixed source branches against in-memory functions, with module autoload disabled. */
class WindowsObservationProjectionTest {
    @Test fun enumNamesAndEmptyVpnListSurviveWindowsPowerShellJson() {
        val empty = projected("vpn-user", "function Get-VpnConnection {}")
        assertThat(empty.state).isEqualTo(SourceState.EMPTY)
        val populated = projected("vpn-user", """
            function Get-VpnConnection {
                [pscustomobject]@{Name='Fixture';TunnelType=[System.DayOfWeek]::Friday;ConnectionStatus=[System.DayOfWeek]::Monday;AuthenticationMethod=@([System.DayOfWeek]::Tuesday);SplitTunneling=@D@false}
            }
        """.trimIndent())
        assertThat(populated.rows.single().fields["TunnelType"]).isEqualTo("Friday")
        assertThat(populated.rows.single().fields["AuthenticationMethod"]).isEqualTo("Tuesday")
        assertThat(populated.rows.single().fields["SplitTunneling"]).isEqualTo("false")
    }

    @Test fun quickModeProjectionUsesDocumentedFieldsInsteadOfMissingProperties() {
        val source = projected("ipsec", """
            function Get-NetIPsecQuickModeSA {
                [pscustomobject]@{Name='Fixture';LocalEndpoint='192.0.2.1';RemoteEndpoint='192.0.2.2';IpProtocol=17;FirstCipherAlgorithm='AES256';FirstIntegrityAlgorithm='SHA256';SecondCipherAlgorithm='None';SecondIntegrityAlgorithm='None';ExplicitCredentials='MUST_NOT_LEAVE_FIXTURE'}
            }
        """.trimIndent())
        val fields = source.rows.single().fields
        assertThat(fields["IpProtocol"]).isEqualTo("17")
        assertThat(fields["FirstCipherAlgorithm"]).isEqualTo("AES256")
        assertThat(fields["FirstIntegrityAlgorithm"]).isEqualTo("SHA256")
        assertThat(fields.keys).doesNotContain("ExplicitCredentials")
        assertThat(fields.values).doesNotContain("MUST_NOT_LEAVE_FIXTURE")
    }

    @Test fun deniedRegistrySectionMakesInstalledAppListIncomplete() {
        val source = projected("installed-apps", """
            function Get-ItemProperty {
                [CmdletBinding()]param([string[]]@D@Path)
                [pscustomobject]@{PSPath='Fixture';DisplayName='Visible fixture';DisplayVersion='1'}
                Write-Error 'Fixture permission failure' -Category PermissionDenied
            }
        """.trimIndent())
        assertThat(source.state).isEqualTo(SourceState.AVAILABLE)
        assertThat(source.complete).isFalse()
        assertThat(source.rows.single().title).isEqualTo("Visible fixture")
    }

    @Test fun firewallRequestsOriginFromActiveStoreWithoutChangingRules() {
        val source = projected("firewall-rules", """
            function Get-NetFirewallRule {
                param([string]@D@PolicyStore,[switch]@D@TracePolicyStore,[string]@D@Enabled)
                if (@D@PolicyStore -ne 'ActiveStore' -or -not @D@TracePolicyStore) {throw 'Missing policy origin request'}
                [pscustomobject]@{Name='Fixture';DisplayName='Fixture firewall rule';PolicyStoreSource='Fixture local policy';Action='Allow'}
            }
            function Get-NetFirewallApplicationFilter {param(@D@AssociatedNetFirewallRule);[pscustomobject]@{Program='Any'}}
            function Get-NetFirewallPortFilter {param(@D@AssociatedNetFirewallRule);[pscustomobject]@{Protocol='TCP';LocalPort='Any';RemotePort='443'}}
            function Get-NetFirewallAddressFilter {param(@D@AssociatedNetFirewallRule);[pscustomobject]@{LocalAddress='Any';RemoteAddress='Any'}}
        """.trimIndent())
        assertThat(source.state).isEqualTo(SourceState.AVAILABLE)
        assertThat(source.rows.single().fields["PolicyStoreSource"]).isEqualTo("Fixture local policy")
    }

    @Test fun proxyUriCredentialsAreHiddenForSocksAndHttpSchemes() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val pureFunctions = requireNotNull(javaClass.getResourceAsStream("/observation.ps1")).bufferedReader().use { it.readText() }.substringBefore("# Convert enum")
        val result = SystemObservationCommandRunner.run(powershellArguments(pureFunctions + "\nPublic-Endpoint 'socks5://fixture-user@192.0.2.1:1080?token=fixture-secret'\nPublic-Endpoint 'https://fixture-user:fixture-password@192.0.2.2:8080/path#fixture-fragment'"), 10_000)
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.output).doesNotContain("fixture-")
        assertThat(result.output).contains("socks5://[hidden]@192.0.2.1:1080")
        assertThat(result.output).contains("https://[hidden]@192.0.2.2:8080/path")
    }

    private fun projected(source: String, fixedMocks: String): app.lernet.engine.net.observation.ObservationSource {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        require(source in setOf("vpn-user", "ipsec", "installed-apps", "firewall-rules"))
        val file = Files.createTempFile("lernet-observation-fixture-", ".ps1")
        try {
            Files.write(file, requireNotNull(javaClass.getResourceAsStream("/observation.ps1")).use { it.readBytes() })
            val script = """
                Import-Module Microsoft.PowerShell.Utility
                @D@PSModuleAutoLoadingPreference='None'
                ${fixedMocks.replace("@D@", "$")}
                & '${file.toString().replace("'", "''")}' -Source '$source'
            """.trimIndent().replace("@D@", "$")
            val result = SystemObservationCommandRunner.run(powershellArguments(script), 10_000)
            assertThat(result.exitCode).isEqualTo(0)
            assertThat(result.timedOut).isFalse()
            return WindowsNetworkObservation.parseSource(WindowsNetworkObservation.definitions.single { it.id == source }, result)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
