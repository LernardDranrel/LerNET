package app.lernet.config.parse

import app.lernet.config.model.ProfileSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JsonConfigParserTest {
    @Test
    fun extractsConcreteOutboundsAndIgnoresBalancer() {
        val raw = fixture("singbox-outbounds.json")
        val result = JsonConfigParser.parse(raw, ProfileSource.JSON_PASTE, "file") as ImportResult.Success
        val draft = result.drafts.single()
        assertThat(draft.outbounds).hasSize(1)
        assertThat(draft.outbounds.single().tag).isEqualTo("edge")
        assertThat(draft.outbounds.single().type).isEqualTo("vless")
    }

    @Test
    fun keepsModernDnsOnImportedDraft() {
        val raw = """
            {
              "dns": {
                "servers": [
                  {"type":"tls","tag":"remote","server":"1.1.1.1"},
                  {"type":"local","tag":"local"}
                ],
                "final":"remote"
              },
              "outbounds":[{"type":"vless","tag":"edge","server":"h","server_port":443,"uuid":"u"}]
            }
        """.trimIndent()
        val result = JsonConfigParser.parse(raw, ProfileSource.JSON_PASTE, "file") as ImportResult.Success
        val dns = result.drafts.single().dnsJson
        assertThat(dns).isNotNull()
        assertThat(dns).contains("\"type\"")
        assertThat(dns).contains("tls")
        assertThat(dns).contains("local")
        assertThat(dns).doesNotContain("\"address\"")
    }

    @Test
    fun preservesXhttpTransportInJson() {
        val raw = """
            {"type":"vless","tag":"x1","server":"h","server_port":443,"uuid":"u",
             "transport":{"type":"xhttp","path":"/x","mode":"auto"}}
        """.trimIndent()
        val result = JsonConfigParser.parse(raw, ProfileSource.JSON_PASTE, "paste") as ImportResult.Success
        assertThat(result.drafts.single().outbounds.single().singBoxJson).contains("\"xhttp\"")
    }

    @Test
    fun acceptsBareOutboundObject() {
        val raw = """{"type":"trojan","tag":"t1","server":"h","server_port":443,"password":"x"}"""
        val result = JsonConfigParser.parse(raw, ProfileSource.JSON_PASTE, "paste") as ImportResult.Success
        assertThat(result.drafts.single().outbounds.single().type).isEqualTo("trojan")
    }

    @Test
    fun rejectsEmptyAndInvalid() {
        assertThat(JsonConfigParser.parse("", ProfileSource.JSON_PASTE, "x")).isInstanceOf(ImportResult.Failure::class.java)
        assertThat(JsonConfigParser.parse("{", ProfileSource.JSON_PASTE, "x")).isInstanceOf(ImportResult.Failure::class.java)
        assertThat(
            JsonConfigParser.parse("""{"outbounds":[{"type":"direct"}]}""", ProfileSource.JSON_PASTE, "x"),
        ).isInstanceOf(ImportResult.Failure::class.java)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")).bufferedReader().readText()
}
