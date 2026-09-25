package app.lernet.engine.compile

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class DnsDependencyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun reportsMissingLocalForDnsRemote() {
        val root = json.parseToJsonElement(
            """
            {"dns":{"servers":[
              {"type":"https","tag":"dns-remote","server":"1.1.1.1","path":"/dns-query",
               "detour":"proxy","domain_resolver":"local"}
            ],"final":"dns-remote"},
            "outbounds":[{"type":"vless","tag":"proxy"},{"type":"direct","tag":"direct"}]}
            """.trimIndent(),
        ).jsonObject
        assertThat(DnsDependency.dangling(root))
            .contains("dependency[local] not found for server[dns-remote]")
    }

    @Test
    fun reportsMissingDetourOutbound() {
        val root = json.parseToJsonElement(
            """
            {"dns":{"servers":[
              {"type":"https","tag":"remote","server":"1.1.1.1","detour":"gone"},
              {"type":"local","tag":"local"}
            ]},
            "outbounds":[{"type":"direct","tag":"direct"}]}
            """.trimIndent(),
        ).jsonObject
        assertThat(DnsDependency.dangling(root))
            .contains("detour[gone] not found for server[remote]")
    }

    @Test
    fun acceptsObjectDomainResolverAndExistingLocal() {
        val root = json.parseToJsonElement(
            """
            {"dns":{"servers":[
              {"type":"https","tag":"remote","server":"1.1.1.1",
               "domain_resolver":{"server":"local","strategy":"ipv4_only"}},
              {"type":"local","tag":"local"}
            ],"final":"remote"},
            "route":{"default_domain_resolver":"local"},
            "outbounds":[{"type":"vless","tag":"proxy"},{"type":"direct","tag":"direct"}]}
            """.trimIndent(),
        ).jsonObject
        assertThat(DnsDependency.dangling(root)).isEmpty()
    }

    @Test
    fun detourToEmptyDirectOutboundFails() {
        val root = json.parseToJsonElement(
            """
            {"dns":{"servers":[
              {"type":"udp","tag":"dns-direct","server":"1.1.1.1","detour":"direct"},
              {"type":"local","tag":"local","detour":"direct"}
            ]},
            "outbounds":[{"type":"vless","tag":"proxy"},{"type":"direct","tag":"direct"}]}
            """.trimIndent(),
        ).jsonObject
        val problems = DnsDependency.dangling(root)
        assertThat(problems).contains("detour[direct] is an empty direct outbound for server[dns-direct]")
        assertThat(problems).contains("detour[direct] is an empty direct outbound for server[local]")
    }

    @Test
    fun detourToDirectWithDialFieldIsNotEmpty() {
        val root = json.parseToJsonElement(
            """
            {"dns":{"servers":[
              {"type":"udp","tag":"dns-direct","server":"1.1.1.1","detour":"direct"}
            ]},
            "outbounds":[{"type":"direct","tag":"direct","bind_interface":"wlan0"}]}
            """.trimIndent(),
        ).jsonObject
        assertThat(DnsDependency.dangling(root)).isEmpty()
    }

    @Test
    fun resolverCycleIsDangling() {
        val root = json.parseToJsonElement(
            """
            {"dns":{"servers":[
              {"type":"udp","tag":"a","server":"1.1.1.1","domain_resolver":"b"},
              {"type":"udp","tag":"b","server":"8.8.8.8","domain_resolver":"a"}
            ]},
            "outbounds":[{"type":"direct","tag":"direct","bind_interface":"wlan0"}]}
            """.trimIndent(),
        ).jsonObject
        assertThat(DnsDependency.dangling(root)).contains("resolver cycle a->b->a")
    }
}
