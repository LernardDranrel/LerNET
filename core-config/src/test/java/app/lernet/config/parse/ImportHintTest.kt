package app.lernet.config.parse

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class ImportHintTest {
    @Test
    fun singleVlessLink() {
        val guess = ImportHint.detect("vless://11111111-1111-1111-1111-111111111111@example.com:443#Node")
        assertThat(guess.mode).isEqualTo(GuessedMode.VLESS)
        assertThat(guess.soft).isEqualTo(SoftHint.NONE)
    }

    @Test
    fun singBoxJsonDocument() {
        val raw = """{"outbounds":[{"type":"vless","tag":"proxy"}]}"""
        val guess = ImportHint.detect(raw)
        assertThat(guess.mode).isEqualTo(GuessedMode.JSON_PASTE)
    }

    @Test
    fun httpsJsonBodyIsJsonUrl() {
        val guess = ImportHint.classifyFetched("application/json", """{"outbounds":[]}""")
        assertThat(guess.mode).isEqualTo(GuessedMode.JSON_URL)
        assertThat(guess.soft).isEqualTo(SoftHint.NONE)
    }

    @Test
    fun httpsBase64BodyIsSubscription() {
        val body = Base64.getEncoder().encodeToString(
            "vless://11111111-1111-1111-1111-111111111111@a:443".toByteArray(),
        )
        val guess = ImportHint.classifyFetched("text/plain", body)
        assertThat(guess.mode).isEqualTo(GuessedMode.SUBSCRIPTION)
        assertThat(guess.soft).isEqualTo(SoftHint.MAYBE_SUBSCRIPTION)
    }

    @Test
    fun proseDoesNotGuessAMode() {
        val guess = ImportHint.detect("это просто абзац без ссылки и без json")
        assertThat(guess.mode).isNull()
        assertThat(guess.soft).isEqualTo(SoftHint.MAYBE_SUBSCRIPTION)
        assertThat(guess.needsFetch).isFalse()
    }

    @Test
    fun severalVlessLinesAreADocument() {
        val raw = """
            vless://11111111-1111-1111-1111-111111111111@a:1
            vless://22222222-2222-2222-2222-222222222222@b:2
        """.trimIndent()
        val guess = ImportHint.detect(raw)
        assertThat(guess.mode).isEqualTo(GuessedMode.SUBSCRIPTION)
        assertThat(guess.document).isTrue()
    }

    @Test
    fun base64DocumentDecodesToVless() {
        val raw = Base64.getEncoder().encodeToString(
            "vless://11111111-1111-1111-1111-111111111111@a:443\nvless://22222222-2222-2222-2222-222222222222@b:443".toByteArray(),
        )
        val guess = ImportHint.detect(raw)
        assertThat(guess.mode).isEqualTo(GuessedMode.SUBSCRIPTION)
        assertThat(guess.document).isTrue()
    }

    @Test
    fun jsonPathSkipsFetch() {
        val guess = ImportHint.detect("https://example.com/node.json")
        assertThat(guess.mode).isEqualTo(GuessedMode.JSON_URL)
        assertThat(guess.needsFetch).isFalse()
    }

    @Test
    fun plainHttpsNeedsFetch() {
        val guess = ImportHint.detect("https://example.com/sub")
        assertThat(guess.mode).isNull()
        assertThat(guess.needsFetch).isTrue()
    }
}
