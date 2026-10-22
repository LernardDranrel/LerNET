package app.lernet.config.redact

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun redactsUuidPasswordAndShareLink() {
        val raw =
            """uuid=11111111-1111-1111-1111-111111111111 "password":"secret" vless://abc@host:443"""
        val redacted = SecretRedactor.redact(raw)
        assertThat(redacted).doesNotContain("11111111-1111-1111-1111-111111111111")
        assertThat(redacted).doesNotContain("secret")
        assertThat(redacted).doesNotContain("vless://abc")
        assertThat(redacted).contains("***")
    }

    @Test
    fun redactsShareLinksOfEveryProtocol() {
        val raw = "trojan://pw1@h:443 ss://YWVzOnB3@h:1 hy2://pw2@h:1 tuic://u:pw3@h:1 vmess://eyJpZCI6InB3NCJ9 hysteria2://pw5@h:1"
        val redacted = SecretRedactor.redact(raw)
        listOf("pw1", "YWVzOnB3", "pw2", "pw3", "eyJpZCI6InB3NCJ9", "pw5").forEach { assertThat(redacted).doesNotContain(it) }
        assertThat(redacted).contains("trojan://***")
        assertThat(redacted).contains("tuic://***")
        assertThat(SecretRedactor.redact("class://keep")).isEqualTo("class://keep")
    }

    @Test
    fun redactsAssembledJsonSecrets() {
        val raw = """{"outbounds":[{"uuid":"11111111-1111-1111-1111-111111111111",""" +
            """"password":"pw","private_key":"pk","pbk":"pub","sid":"abcd"}]}"""
        val redacted = SecretRedactor.redact(raw)
        assertThat(redacted).doesNotContain("11111111-1111-1111-1111-111111111111")
        assertThat(redacted).doesNotContain("\"pw\"")
        assertThat(redacted).doesNotContain("\"pk\"")
        assertThat(redacted).contains("\"uuid\":\"***\"")
        assertThat(redacted).contains("\"pbk\":\"***\"")
        assertThat(redacted).contains("\"sid\":\"***\"")
    }

    @Test
    fun redactsHeaderPbkAndSidBeforeDisk() {
        val raw = "header uuid=11111111-1111-1111-1111-111111111111 pbk=pub sid=abcd"
        val redacted = SecretRedactor.redact(raw)
        assertThat(redacted).doesNotContain("11111111-1111-1111-1111-111111111111")
        assertThat(redacted).doesNotContain("pbk=pub")
        assertThat(redacted).doesNotContain("sid=abcd")
        assertThat(redacted).contains("pbk:***")
        assertThat(redacted).contains("sid:***")
    }
}
