package app.lernet.config.parse

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class SubscriptionParserTest {
    @Test
    fun parsesPlainVlessLines() {
        val body = """
            ${fixture("vless-tls-ws.txt").trim()}
            ${fixture("vless-reality.txt").trim()}
        """.trimIndent()
        val result = SubscriptionParser.parse(body, "https://example.com/sub") as ImportResult.Success
        assertThat(result.drafts).hasSize(2)
        assertThat(result.drafts.all { it.subscriptionUrl == "https://example.com/sub" }).isTrue()
    }

    @Test
    fun decodesBase64Subscription() {
        val plain = fixture("vless-tls-ws.txt").trim() + "\n" + fixture("vless-reality.txt").trim()
        val encoded = Base64.getEncoder().encodeToString(plain.toByteArray())
        val result = SubscriptionParser.parse(encoded, "https://example.com/sub") as ImportResult.Success
        assertThat(result.drafts).hasSize(2)
    }

    @Test
    fun parsesJsonSubscription() {
        val result = SubscriptionParser.parse(fixture("singbox-outbounds.json"), "https://example.com/json")
            as ImportResult.Success
        assertThat(result.drafts.single().outbounds.single().tag).isEqualTo("edge")
    }

    @Test
    fun skipsUnknownShareLinksButKeepsVless() {
        val body = "ss://not-supported\n${fixture("vless-tls-ws.txt").trim()}"
        val result = SubscriptionParser.parse(body, "https://example.com/sub") as ImportResult.Success
        assertThat(result.drafts).hasSize(1)
    }

    @Test
    fun failsWhenNothingParsed() {
        val result = SubscriptionParser.parse("ss://only", "https://example.com/sub") as ImportResult.Failure
        assertThat(result.errors).isNotEmpty()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")).bufferedReader().readText()
}
