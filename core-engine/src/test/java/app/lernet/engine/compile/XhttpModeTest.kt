package app.lernet.engine.compile

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class XhttpModeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun preservesEveryXhttpModeIncludingStreamOne() {
        listOf("auto", "packet-up", "stream-up", "stream-one", "stream-on").forEach { mode ->
            val outbound = json.parseToJsonElement(
                """{"type":"vless","transport":{"type":"xhttp","mode":"$mode","path":"/"}}""",
            ).jsonObject
            val result = XhttpMode.normalize(outbound)
            val transport = result.outbound["transport"]!!.jsonObject
            assertThat(result.remapped).isFalse()
            assertThat(transport["mode"]!!.jsonPrimitive.content).isEqualTo(mode)
            assertThat(transport["xmux"]!!.jsonObject["max_concurrency"]!!.jsonPrimitive.content)
                .isEqualTo(XhttpMode.MUX_CONCURRENCY)
            assertThat(result.note).contains("xhttp mode $mode preserved")
            assertThat(result.note).contains("xmux max_concurrency=${XhttpMode.MUX_CONCURRENCY}")
        }
    }

    @Test
    fun keepsProfileXmuxAndDoesNotRewriteMode() {
        val outbound = json.parseToJsonElement(
            """{"type":"vless","transport":{"type":"xhttp","mode":"stream-one","path":"/c","xmux":{"max_concurrency":"1-1"}}}""",
        ).jsonObject
        val result = XhttpMode.normalize(outbound)
        val transport = result.outbound["transport"]!!.jsonObject
        assertThat(result.remapped).isFalse()
        assertThat(transport["mode"]!!.jsonPrimitive.content).isEqualTo("stream-one")
        assertThat(transport["xmux"]!!.jsonObject["max_concurrency"]!!.jsonPrimitive.content).isEqualTo("1-1")
        assertThat(result.note).contains("xmux kept")
    }

    @Test
    fun ignoresNonXhttpTransport() {
        val outbound = json.parseToJsonElement(
            """{"type":"vless","transport":{"type":"ws","mode":"stream-one"}}""",
        ).jsonObject
        val result = XhttpMode.normalize(outbound)
        assertThat(result.remapped).isFalse()
        assertThat(result.note).isNull()
    }
}
