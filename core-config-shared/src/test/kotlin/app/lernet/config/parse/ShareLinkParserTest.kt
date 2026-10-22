package app.lernet.config.parse

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ShareLinkParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun outbound(link: String): JsonObject {
        val result = ShareLinkParser.parse(link)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        return json.parseToJsonElement((result as ImportResult.Success).drafts.single().outbounds.single().singBoxJson).jsonObject
    }

    private fun failure(link: String): String =
        (ShareLinkParser.parse(link) as ImportResult.Failure).errors.joinToString { it.message }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    @Test fun vmessBase64JsonWithWebSocketAndTls() {
        val body = """{"v":"2","ps":"Германия","add":"de.example.com","port":"443","id":"11111111-1111-1111-1111-111111111111","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ray?ed=2048","tls":"tls","sni":"cdn.example.com","fp":"firefox"}"""
        val link = "vmess://" + Base64.getEncoder().encodeToString(body.toByteArray())
        val result = ShareLinkParser.parse(link) as ImportResult.Success
        assertThat(result.drafts.single().name).isEqualTo("Германия")
        val obj = json.parseToJsonElement(result.drafts.single().outbounds.single().singBoxJson).jsonObject
        assertThat(obj.str("type")).isEqualTo("vmess")
        assertThat(obj.str("server")).isEqualTo("de.example.com")
        assertThat(obj.str("server_port")).isEqualTo("443")
        assertThat(obj.str("security")).isEqualTo("auto")
        val tls = obj["tls"]!!.jsonObject
        assertThat(tls.str("server_name")).isEqualTo("cdn.example.com")
        assertThat(tls["utls"]!!.jsonObject.str("fingerprint")).isEqualTo("firefox")
        val transport = obj["transport"]!!.jsonObject
        assertThat(transport.str("type")).isEqualTo("ws")
        assertThat(transport.str("path")).isEqualTo("/ray")
        assertThat(transport.str("max_early_data")).isEqualTo("2048")
        assertThat(transport["headers"]!!.jsonObject.str("Host")).isEqualTo("cdn.example.com")
    }

    @Test fun vmessWithoutFingerprintLeavesItToTheAssembler() {
        val body = """{"add":"h.example","port":443,"id":"u","net":"tcp","tls":"tls"}"""
        val obj = outbound("vmess://" + Base64.getEncoder().encodeToString(body.toByteArray()))
        assertThat(obj["tls"]!!.jsonObject["utls"]).isNull()
    }

    @Test fun vmessTcpHttpObfuscationIsRefusedHonestly() {
        val body = """{"add":"h.example","port":443,"id":"u","net":"tcp","type":"http"}"""
        assertThat(failure("vmess://" + Base64.getEncoder().encodeToString(body.toByteArray()))).contains("не поддерживается")
    }

    @Test fun vmessUnknownTransportIsRefused() {
        val body = """{"add":"h.example","port":443,"id":"u","net":"kcp"}"""
        assertThat(failure("vmess://" + Base64.getEncoder().encodeToString(body.toByteArray()))).contains("«kcp»")
    }

    @Test fun trojanDefaultsToTlsAndKeepsPeerAsServerName() {
        val obj = outbound("trojan://p%40ss@t.example.com:8443?peer=front.example.com&type=grpc&serviceName=svc#Trojan")
        assertThat(obj.str("type")).isEqualTo("trojan")
        assertThat(obj.str("password")).isEqualTo("p@ss")
        assertThat(obj["tls"]!!.jsonObject.str("server_name")).isEqualTo("front.example.com")
        assertThat(obj["transport"]!!.jsonObject.str("service_name")).isEqualTo("svc")
    }

    @Test fun trojanReality() {
        val obj = outbound("trojan://pw@r.example.com:443?security=reality&sni=www.example.com&pbk=KEY&sid=ab&fp=chrome")
        val tls = obj["tls"]!!.jsonObject
        assertThat(tls["reality"]!!.jsonObject.str("public_key")).isEqualTo("KEY")
        assertThat(tls["utls"]!!.jsonObject.str("fingerprint")).isEqualTo("chrome")
    }

    @Test fun shadowsocksSip002Base64User() {
        val user = Base64.getUrlEncoder().withoutPadding().encodeToString("chacha20-ietf-poly1305:secret".toByteArray())
        val result = ShareLinkParser.parse("ss://$user@ss.example.com:8388#%D0%9C%D0%BE%D0%B9") as ImportResult.Success
        assertThat(result.drafts.single().name).isEqualTo("Мой")
        val obj = json.parseToJsonElement(result.drafts.single().outbounds.single().singBoxJson).jsonObject
        assertThat(obj.str("type")).isEqualTo("shadowsocks")
        assertThat(obj.str("method")).isEqualTo("chacha20-ietf-poly1305")
        assertThat(obj.str("password")).isEqualTo("secret")
        assertThat(obj.str("server_port")).isEqualTo("8388")
    }

    @Test fun shadowsocks2022PercentEncodedUser() {
        val obj = outbound("ss://2022-blake3-aes-256-gcm:AAAA%2BBBB%3D@1.2.3.4:443")
        assertThat(obj.str("method")).isEqualTo("2022-blake3-aes-256-gcm")
        assertThat(obj.str("password")).isEqualTo("AAAA+BBB=")
    }

    @Test fun shadowsocksLegacyWholeBase64() {
        val body = Base64.getEncoder().encodeToString("aes-256-gcm:pw@old.example.com:1234".toByteArray())
        val obj = outbound("ss://$body#Old")
        assertThat(obj.str("server")).isEqualTo("old.example.com")
        assertThat(obj.str("server_port")).isEqualTo("1234")
    }

    @Test fun shadowsocksObfsPlugin() {
        val user = Base64.getEncoder().encodeToString("aes-128-gcm:pw".toByteArray())
        val obj = outbound("ss://$user@h.example:80/?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dexample.com")
        assertThat(obj.str("plugin")).isEqualTo("obfs-local")
        assertThat(obj.str("plugin_opts")).isEqualTo("obfs=http;obfs-host=example.com")
    }

    @Test fun shadowsocksUnknownMethodAndPluginAreRefused() {
        assertThat(failure("ss://" + Base64.getEncoder().encodeToString("bf-cfb:pw".toByteArray()) + "@h:1")).contains("«bf-cfb»")
        val user = Base64.getEncoder().encodeToString("aes-128-gcm:pw".toByteArray())
        assertThat(failure("ss://$user@h:1?plugin=kcptun")).contains("«kcptun»")
    }

    @Test fun hysteria2WithObfsAndPortHopping() {
        val result = ShareLinkParser.parse("hysteria2://user:pa%20ss@hy.example.com:443,20000-30000/?sni=real.example.com&obfs=salamander&obfs-password=ob&insecure=1#Hy") as ImportResult.Success
        val obj = json.parseToJsonElement(result.drafts.single().outbounds.single().singBoxJson).jsonObject
        assertThat(obj.str("type")).isEqualTo("hysteria2")
        assertThat(obj.str("server_port")).isEqualTo("443")
        assertThat(obj["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("20000:30000")
        assertThat(obj.str("password")).isEqualTo("user:pa ss")
        assertThat(obj["obfs"]!!.jsonObject.str("password")).isEqualTo("ob")
        val tls = obj["tls"]!!.jsonObject
        assertThat(tls.str("server_name")).isEqualTo("real.example.com")
        assertThat(tls.str("insecure")).isEqualTo("true")
        assertThat(tls["alpn"]!!.jsonArray.single().jsonPrimitive.content).isEqualTo("h3")
    }

    @Test fun hy2AliasAndPinIsReportedNotSilentlyDropped() {
        val result = ShareLinkParser.parse("hy2://pw@h.example:443?pinSHA256=AB:CD") as ImportResult.Success
        assertThat(result.unusedParts.single()).contains("pinSHA256")
    }

    @Test fun tuic() {
        val obj = outbound("tuic://11111111-1111-1111-1111-111111111111:pw@[2001:db8::1]:443?congestion_control=bbr&udp_relay_mode=quic&alpn=h3&allow_insecure=1#T")
        assertThat(obj.str("type")).isEqualTo("tuic")
        assertThat(obj.str("server")).isEqualTo("2001:db8::1")
        assertThat(obj.str("uuid")).isEqualTo("11111111-1111-1111-1111-111111111111")
        assertThat(obj.str("password")).isEqualTo("pw")
        assertThat(obj.str("congestion_control")).isEqualTo("bbr")
        assertThat(obj["tls"]!!.jsonObject.str("insecure")).isEqualTo("true")
    }

    @Test fun vlessStillGoesThroughVlessParser() {
        val obj = outbound("vless://11111111-1111-1111-1111-111111111111@v.example.com:443?security=tls&type=ws&path=%2Fws#V")
        assertThat(obj.str("type")).isEqualTo("vless")
    }

    @Test fun unknownSchemeAndBadPortFailWithReason() {
        assertThat(failure("wireguard://x@h:1")).contains("не поддерживается")
        assertThat(failure("trojan://pw@h.example:99999")).contains("порт")
    }

    @Test fun importHintRecognisesEveryScheme() {
        listOf("trojan://pw@h:1", "ss://YWVzLTEyOC1nY206cHc=@h:1", "hy2://pw@h:1", "tuic://u:p@h:1", "vmess://e30=").forEach {
            assertThat(ImportHint.detect(it).mode).isEqualTo(GuessedMode.VLESS)
        }
        assertThat(ImportHint.detect("trojan://pw@h:1\nss://x@h:2").document).isTrue()
    }
}
