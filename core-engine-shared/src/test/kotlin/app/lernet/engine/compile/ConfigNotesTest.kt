package app.lernet.engine.compile

import app.lernet.config.model.DnsPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConfigNotesTest {
    private val dns = """
        {"servers":[
          {"type":"tls","tag":"remote","server":"1.1.1.1","detour":"proxy"},
          {"type":"https","tag":"doh","server":"dns.example","domain_resolver":"ghost"},
          {"type":"udp","tag":"plain","server":"9.9.9.9"}
        ],"final":"missing"}
    """.trimIndent()

    @Test
    fun underlayPolicyShowsDroppedAndRemappedDnsOnScreen() {
        val prepared = DnsBlock.prepare(dns, proxyTag = "proxy", policy = DnsPolicy.UNDERLAY)
        val shown = ConfigNotes.userVisible(prepared.notes)
        assertThat(shown).contains("основной DNS-сервер «missing» в конфиге не найден — используется «dns-direct»")
        assertThat(shown.any { it.startsWith("DNS-серверы remote, doh не используются") }).isTrue()
        assertThat(shown.any { it.contains("резолвер «ghost»") }).isTrue()
    }

    @Test
    fun lernetOwnAdditionsStayInTheJournalOnly() {
        val prepared = DnsBlock.prepare(null, proxyTag = "proxy", policy = DnsPolicy.UNDERLAY)
        assertThat(prepared.notes).isNotEmpty()
        assertThat(ConfigNotes.userVisible(prepared.notes)).isEmpty()
    }

    @Test
    fun translatesEachKnownNote() {
        assertThat(ConfigNotes.translate("dns remapped domain_resolver a→local tag=doh"))
            .isEqualTo("у DNS-сервера «doh» резолвер «a» не найден — используется «local»")
        assertThat(ConfigNotes.translate("dns dropped domain_resolver doh tag=doh (self or missing)"))
            .isEqualTo("у DNS-сервера «doh» резолвер «doh» не найден или указывает сам на себя — убран")
        assertThat(ConfigNotes.translate("dns dropped detour=direct tag=x (lx.8 empty direct outbound)")).isNull()
    }
}
