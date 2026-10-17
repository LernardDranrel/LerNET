package app.lernet.config.parse

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class VlessFingerprintTest {
    private fun outbound(link: String): JsonObject {
        val draft = (VlessParser.parse(link) as ImportResult.Success).drafts.single()
        return Json.parseToJsonElement(draft.outbounds.single().singBoxJson).jsonObject
    }

    private val uuid = "11111111-1111-1111-1111-111111111111"

    @Test
    fun realityWithoutFpGetsChrome() {
        val o = outbound("vless://$uuid@example.com:443?security=reality&pbk=KEY&sid=ab&sni=www.example.org#r")
        val utls = o["tls"]!!.jsonObject["utls"]!!.jsonObject
        assertThat(utls["enabled"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(utls["fingerprint"]!!.jsonPrimitive.content).isEqualTo("chrome")
    }

    @Test
    fun tlsWithoutFpGetsChromeAndExplicitFpIsKept() {
        val plain = outbound("vless://$uuid@example.com:443?security=tls&sni=cdn.example.com#t")
        assertThat(plain["tls"]!!.jsonObject["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content).isEqualTo("chrome")
        val firefox = outbound("vless://$uuid@example.com:443?security=tls&fp=Firefox#t")
        assertThat(firefox["tls"]!!.jsonObject["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content).isEqualTo("firefox")
    }

    @Test
    fun noTlsMeansNoFingerprint() {
        assertThat(outbound("vless://$uuid@example.com:80?type=ws#n")["tls"]).isNull()
    }

    @Test
    fun encryptionGoesToEncryptionNotPacketEncoding() {
        val o = outbound("vless://$uuid@example.com:443?security=reality&pbk=K&encryption=mlkem768x25519plus.native.0rtt.abc#e")
        assertThat(o["encryption"]!!.jsonPrimitive.content).isEqualTo("mlkem768x25519plus.native.0rtt.abc")
        assertThat(o["packet_encoding"]).isNull()
        val none = outbound("vless://$uuid@example.com:443?security=tls&encryption=none&packetEncoding=xudp#n")
        assertThat(none["encryption"]).isNull()
        assertThat(none["packet_encoding"]!!.jsonPrimitive.content).isEqualTo("xudp")
    }
}
