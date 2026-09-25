package app.lernet.config.parse

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class VlessParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesTlsWsLink() {
        val link = fixture("vless-tls-ws.txt")
        val result = VlessParser.parse(link)
        val draft = (result as ImportResult.Success).drafts.single()
        assertThat(draft.name).isEqualTo("Node One")
        assertThat(draft.outbounds).hasSize(1)
        val obj = json.parseToJsonElement(draft.outbounds.single().singBoxJson).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("vless")
        assertThat(obj["server"]?.jsonPrimitive?.content).isEqualTo("example.com")
        assertThat(obj["uuid"]?.jsonPrimitive?.content).isEqualTo("11111111-1111-1111-1111-111111111111")
        val tls = obj["tls"]!!.jsonObject
        assertThat(tls["server_name"]?.jsonPrimitive?.content).isEqualTo("cdn.example.com")
        val transport = obj["transport"]!!.jsonObject
        assertThat(transport["type"]?.jsonPrimitive?.content).isEqualTo("ws")
        assertThat(transport["path"]?.jsonPrimitive?.content).isEqualTo("/ws")
    }

    @Test
    fun parsesXhttpLink() {
        val result = VlessParser.parse(fixture("vless-xhttp.txt")) as ImportResult.Success
        val obj = json.parseToJsonElement(result.drafts.single().outbounds.single().singBoxJson).jsonObject
        val transport = obj["transport"]!!.jsonObject
        assertThat(transport["type"]?.jsonPrimitive?.content).isEqualTo("xhttp")
        assertThat(transport["path"]?.jsonPrimitive?.content).isEqualTo("/xhttp")
        assertThat(transport["host"]?.jsonPrimitive?.content).isEqualTo("cdn.example.com")
        assertThat(transport["mode"]?.jsonPrimitive?.content).isEqualTo("auto")
    }

    @Test
    fun parsesRealityLink() {
        val result = VlessParser.parse(fixture("vless-reality.txt")) as ImportResult.Success
        val obj = json.parseToJsonElement(result.drafts.single().outbounds.single().singBoxJson).jsonObject
        val reality = obj["tls"]!!.jsonObject["reality"]!!.jsonObject
        assertThat(reality["enabled"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(reality["public_key"]?.jsonPrimitive?.content).isEqualTo("PUBLICKEY")
        assertThat(obj["flow"]?.jsonPrimitive?.content).isEqualTo("xtls-rprx-vision")
    }

    @Test
    fun rejectsEmpty() {
        val result = VlessParser.parse("   ") as ImportResult.Failure
        assertThat(result.errors.single().field).isEqualTo("link")
    }

    @Test
    fun rejectsNonVless() {
        val result = VlessParser.parse("ss://abc") as ImportResult.Failure
        assertThat(result.errors.single().field).isEqualTo("link")
    }

    @Test
    fun rejectsMissingUuid() {
        val result = VlessParser.parse("vless://example.com:443") as ImportResult.Failure
        assertThat(result.errors.any { it.field == "uuid" }).isTrue()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")).bufferedReader().readText()
}
