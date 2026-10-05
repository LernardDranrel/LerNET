package app.lernet.config.policy

import app.lernet.config.transfer.TransferOutbound
import app.lernet.routing.RoutePlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalExitProfilesTest {
    private val binding = VerifiedInterfaceBinding("{12345678-1234-5678-9ABC-1234567890AB}", "Corporate VPN", 17)
    private fun socks(name: String = "External") = ExternalExitRequest(
        ExternalExitKind.SOCKS5, name, "пример.рф", 1080,
        "account", "private-password"
    )

    @Test
    fun `validated external profile normalizes host and never exposes credentials in diagnostics`() {
        val request = socks()
        val profile = ExternalExitProfiles.build(request, "profile", "outbound")
        val raw = Json.parseToJsonElement(profile.outbounds.single().singBoxJson).jsonObject
        assertEquals("xn--e1afmkfd.xn--p1ai", raw.getValue("server").jsonPrimitive.content)
        assertEquals("private-password", raw.getValue("password").jsonPrimitive.content)
        assertFalse(request.toString().contains("private-password"))
        assertFalse(request.toString().contains("account"))
        assertTrue(ExternalExitProfiles.validate(request).isEmpty())
    }

    @Test
    fun `invalid endpoint auth and binding fail without echoing sensitive values`() {
        listOf(
            ExternalExitRequest(ExternalExitKind.HTTP, "External", "https://secret.invalid", 8080),
            ExternalExitRequest(ExternalExitKind.HTTP, "External", "host.invalid", 70000),
            ExternalExitRequest(ExternalExitKind.HTTP, "External", "host.invalid", 8080, "a:b", "secret"),
            ExternalExitRequest(ExternalExitKind.SOCKS5, "External", "host.invalid", 1080, "a", "secret\nvalue"),
            ExternalExitRequest(ExternalExitKind.SOCKS5, "External", "host.invalid", 1080, "a", "я".repeat(128)),
            ExternalExitRequest(ExternalExitKind.CORPORATE_INTERFACE, "Corporate", binding = binding.copy(guid = "wrong")),
        ).forEach { request ->
            val errors = ExternalExitProfiles.validate(request)
            assertFalse(errors.isEmpty())
            assertFalse(errors.any { "secret" in it })
        }
    }

    @Test
    fun `editing selected external outbound preserves other outbounds identity layout and custom DNS`() {
        val initial = ExternalExitProfiles.build(socks(), "profile", "outbound")
        val other = TransferOutbound("other", "other", "direct", "{\"type\":\"direct\",\"tag\":\"other\"}")
        val before = initial.copy(
            outbounds = initial.outbounds + other, dnsPolicy = "PROFILE", dnsJson = "{\"custom\":true}",
            canvasLayout = "{\"node-root\":{\"x\":10,\"y\":20}}"
        )
        val changed = ExternalExitProfiles.build(socks("Renamed"), before.id, before.selectedOutboundId, before)
        assertEquals(other, changed.outbounds.last())
        assertEquals(before.id, changed.id)
        assertEquals(before.selectedOutboundId, changed.selectedOutboundId)
        assertEquals(before.canvasLayout, changed.canvasLayout)
        assertEquals(before.dnsJson, changed.dnsJson)
        assertNotEquals(ExternalExitProfiles.fingerprint(before), ExternalExitProfiles.fingerprint(changed))
    }

    @Test
    fun `bounded form refuses advanced selected fields rather than removing them`() {
        val before = ExternalExitProfiles.build(socks(), "profile", "outbound").let { profile ->
            profile.copy(
                outbounds = profile.outbounds.map { outbound ->
                    outbound.copy(
                        singBoxJson =
                        JsonObject(
                            Json.parseToJsonElement(outbound.singBoxJson).jsonObject +
                                ("bind_interface" to JsonPrimitive("Advanced"))
                        ).toString()
                    )
                }
            )
        }
        assertNull(ExternalExitProfiles.describe(before))
        assertThrows(IllegalArgumentException::class.java) {
            ExternalExitProfiles.build(socks(), before.id, before.selectedOutboundId, before)
        }
        assertTrue(before.outbounds.single().singBoxJson.contains("Advanced"))
    }

    @Test
    fun `bounded form never coerces malformed imported field types`() {
        val before = ExternalExitProfiles.build(socks(), "profile", "outbound")
        val raw = Json.parseToJsonElement(before.outbounds.single().singBoxJson).jsonObject
        val quotedPort = before.copy(
            outbounds = before.outbounds.map {
                it.copy(
                    singBoxJson = JsonObject(raw + ("server_port" to JsonPrimitive("1080"))).toString()
                )
            }
        )
        assertNull(ExternalExitProfiles.describe(quotedPort))
        val invalidId = "id\n"
        val error = assertThrows(IllegalArgumentException::class.java) { ExternalExitProfiles.build(socks(), invalidId, "outbound") }
        assertFalse(error.message.orEmpty().contains(invalidId))
    }

    @Test
    fun `platform capability follows only reachable corporate dependencies`() {
        val corporate = ExternalExitProfiles.build(
            ExternalExitRequest(ExternalExitKind.CORPORATE_INTERFACE, "Corporate", binding = binding),
            "corporate", "corporate-out"
        ).outbounds.single()
        val external = ExternalExitProfiles.build(socks(), "external", "external-out")
        val dormant = external.copy(
            outbounds = external.outbounds + corporate.copy(
                tag = "corporate",
                singBoxJson = JsonObject(
                    Json.parseToJsonElement(corporate.singBoxJson).jsonObject +
                        ("tag" to JsonPrimitive("corporate"))
                ).toString()
            )
        )
        assertNull(ExternalExitProfiles.platformRequirement(dormant))
        val linked = dormant.copy(
            outbounds = dormant.outbounds.map {
                if (it.id == external.selectedOutboundId) {
                    it.copy(
                        singBoxJson = JsonObject(
                            Json.parseToJsonElement(it.singBoxJson).jsonObject +
                                ("detour" to JsonPrimitive("corporate"))
                        ).toString()
                    )
                } else {
                    it
                }
            }
        )
        assertEquals(RoutePlatform.WINDOWS, ExternalExitProfiles.platformRequirement(linked))
        assertEquals(
            "Corporate VPN",
            ExternalExitProfiles.binding(
                linked.outbounds.last().singBoxJson.let {
                    Json.parseToJsonElement(it).jsonObject
                }
            )?.name
        )
    }

    @Test
    fun `corporate identity is typed normalized and DNS is attached to that exit`() {
        val profile = ExternalExitProfiles.build(
            ExternalExitRequest(
                ExternalExitKind.CORPORATE_INTERFACE, "Corporate",
                binding = binding, dnsServer = "10.0.0.53"
            ),
            "corporate", "out"
        )
        val parsed = requireNotNull(ExternalExitProfiles.binding(profile))
        assertEquals("12345678-1234-5678-9abc-1234567890ab", parsed.guid)
        assertEquals(17, parsed.index)
        assertEquals(RoutePlatform.WINDOWS, ExternalExitProfiles.platformRequirement(profile))
        assertEquals("PROFILE", profile.dnsPolicy)
        assertTrue(requireNotNull(profile.dnsJson).contains("10.0.0.53"))
        assertTrue(requireNotNull(profile.dnsJson).contains("\"detour\":\"proxy\""))
        val raw = Json.parseToJsonElement(profile.outbounds.single().singBoxJson).jsonObject
        val bad = JsonObject(
            raw + (
                ExternalExitProfiles.INTERFACE_FIELD to JsonObject(
                    raw.getValue(ExternalExitProfiles.INTERFACE_FIELD).jsonObject + ("index" to JsonPrimitive("17"))
                )
                )
        )
        assertThrows(IllegalArgumentException::class.java) { ExternalExitProfiles.binding(bad) }
    }

    @Test
    fun `corporate generated DNS becomes TCP on HTTP switch while custom corporate DNS stays read only`() {
        val before = ExternalExitProfiles.build(
            ExternalExitRequest(ExternalExitKind.CORPORATE_INTERFACE, "Corporate", binding = binding), "id", "out"
        )
        val http = ExternalExitProfiles.build(
            ExternalExitRequest(ExternalExitKind.HTTP, "HTTP", "proxy.invalid", 8080), "id", "out", before
        )
        assertTrue(requireNotNull(http.dnsJson).contains("\"type\":\"tcp\""))
        val advanced = before.copy(dnsJson = "{\"servers\":[],\"final\":\"advanced\"}")
        assertNull(ExternalExitProfiles.describe(advanced))
        assertThrows(IllegalArgumentException::class.java) {
            ExternalExitProfiles.build(
                ExternalExitRequest(ExternalExitKind.CORPORATE_INTERFACE, "Renamed", binding = binding), "id", "out", advanced
            )
        }
    }
}
