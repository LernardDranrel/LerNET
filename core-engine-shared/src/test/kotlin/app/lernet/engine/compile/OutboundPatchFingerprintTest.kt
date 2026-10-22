package app.lernet.engine.compile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundPatchFingerprintTest {
    private val beta5 =
        """{"type":"vless","tag":"proxy","server":"a.example","server_port":443,"uuid":"u",""" +
            """"tls":{"enabled":true,"server_name":"a.example","utls":{"enabled":true,"fingerprint":"chrome"}}}"""
    private val plain = """{"type":"vless","tag":"proxy","server":"a.example","server_port":443,"uuid":"u"}"""

    private fun utls(json: String): JsonObject? =
        Json.parseToJsonElement(json).jsonObject["tls"]?.jsonObject?.get("utls") as? JsonObject

    @Test
    fun readsFingerprintAsWritten() {
        val owned = OutboundPatch.read(beta5)
        assertTrue(owned.hasTls)
        assertEquals("chrome", owned.fingerprint)
        assertFalse(OutboundPatch.read(plain).hasTls)
        assertEquals("", OutboundPatch.read(plain).fingerprint)
    }

    @Test
    fun nullKeepsProfileFingerprint() {
        val out = OutboundPatch.write(beta5, "a.example", 443, "a.example", "", "")
        assertEquals("chrome", utls(out)?.get("fingerprint")?.jsonPrimitive?.content)
    }

    @Test
    fun choiceIsWrittenLowercase() {
        val out = OutboundPatch.write(beta5, "a.example", 443, "a.example", "", "", fingerprint = "Firefox")
        assertEquals("firefox", utls(out)?.get("fingerprint")?.jsonPrimitive?.content)
        assertEquals("true", utls(out)?.get("enabled")?.jsonPrimitive?.content)
    }

    @Test
    fun blankFallsBackToDefault() {
        val out = OutboundPatch.write(beta5, "a.example", 443, "a.example", "", "", fingerprint = "")
        assertNull(utls(out)?.get("fingerprint"))
        assertEquals("", OutboundPatch.read(out).fingerprint)
    }

    @Test
    fun noTlsNoFingerprint() {
        val out = OutboundPatch.write(plain, "a.example", 443, "", "", "", fingerprint = "chrome")
        assertNull(Json.parseToJsonElement(out).jsonObject["tls"])
    }
}
