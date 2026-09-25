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
