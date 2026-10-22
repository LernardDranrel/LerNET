package app.lernet.engine.compile

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class TlsHandshakeTest {
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun tls(o: JsonObject) = o["tls"]!!.jsonObject
    private val reality = """{"type":"vless","tag":"p","server":"a","server_port":443,"uuid":"u",
        "tls":{"enabled":true,"server_name":"x","reality":{"enabled":true,"public_key":"k"}}}"""

    @Test
    fun profileWithoutUtlsGetsFirefox() {
        assertThat(TlsHandshake.DEFAULT_FINGERPRINT).isEqualTo("firefox")
        val out = TlsHandshake.apply(obj(reality), EngineDefaults())
        assertThat(tls(out)["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content).isEqualTo("firefox")
        assertThat(tls(out)["record_fragment"]).isNull()
        assertThat(tls(out)["reality"]!!.jsonObject["key_share"]).isNull()
    }

    @Test
    fun utlsWithoutFingerprintFromTheNewParserGetsFirefox() {
        val raw = reality.replace("\"server_name\":\"x\"", "\"server_name\":\"x\",\"utls\":{\"enabled\":true}")
        val utls = tls(TlsHandshake.apply(obj(raw), EngineDefaults()))["utls"]!!.jsonObject
        assertThat(utls["fingerprint"]!!.jsonPrimitive.content).isEqualTo("firefox")
        assertThat(utls["enabled"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun plainTlsWithoutRealityGetsTheSameBrowserShape() {
        val trojan = obj(
            """{"type":"trojan","tag":"t","server":"a","server_port":443,"password":"p",""" +
                """"tls":{"enabled":true,"server_name":"a"}}""",
        )
        assertThat(tls(TlsHandshake.apply(trojan, EngineDefaults()))["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
            .isEqualTo("firefox")
    }

    @Test
    fun profileChromeIsKeptAsTheUsersChoice() {
        val raw = reality.replace("\"server_name\":\"x\"", "\"server_name\":\"x\",\"utls\":{\"enabled\":true,\"fingerprint\":\"chrome\"}")
        val utls = tls(TlsHandshake.apply(obj(raw), EngineDefaults()))["utls"]!!.jsonObject
        assertThat(utls["fingerprint"]!!.jsonPrimitive.content).isEqualTo("chrome")
    }

    @Test
    fun profileFingerprintWins() {
        val raw = reality.replace("\"server_name\":\"x\"", "\"server_name\":\"x\",\"utls\":{\"enabled\":true,\"fingerprint\":\"firefox\"}")
        assertThat(tls(TlsHandshake.apply(obj(raw), EngineDefaults()))["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content).isEqualTo("firefox")
    }

    @Test
    fun optInFragmentAndKeyShare() {
        val out = TlsHandshake.apply(obj(reality), EngineDefaults(tlsRecordFragment = true, realityKeyShare = "classical"))
        assertThat(tls(out)["record_fragment"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(tls(out)["reality"]!!.jsonObject["key_share"]!!.jsonPrimitive.content).isEqualTo("classical")
    }

    @Test
    fun quicAndPlainOutboundsUntouched() {
        val hy = obj("""{"type":"hysteria2","tag":"h","server":"a","server_port":443,"password":"p","tls":{"enabled":true}}""")
        assertThat(TlsHandshake.apply(hy, EngineDefaults(tlsRecordFragment = true))).isEqualTo(hy)
        val ss = obj("""{"type":"shadowsocks","tag":"s","server":"a","server_port":1,"method":"aes-128-gcm","password":"p"}""")
        assertThat(TlsHandshake.apply(ss, EngineDefaults(tlsRecordFragment = true))).isEqualTo(ss)
    }

    @Test
    fun keyShareStorageIsTolerant() {
        assertThat(RealityKeyShare.fromStorage(null)).isEqualTo(RealityKeyShare.AUTO)
        assertThat(RealityKeyShare.fromStorage("hybrid")).isEqualTo(RealityKeyShare.HYBRID)
        assertThat(RealityKeyShare.fromStorage("CLASSICAL")).isEqualTo(RealityKeyShare.CLASSICAL)
    }
}
