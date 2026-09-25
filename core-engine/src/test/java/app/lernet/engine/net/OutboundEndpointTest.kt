package app.lernet.engine.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutboundEndpointTest {
    @Test
    fun parsesServerAndPortFromVlessJson() {
        val endpoint = OutboundEndpoint.parse(
            """{"type":"vless","tag":"proxy","server":"151.1.2.3","server_port":443}""",
        )
        assertThat(endpoint).isEqualTo(OutboundEndpoint("151.1.2.3", 443))
    }

    @Test
    fun picksProxyOutboundFromAssembledConfig() {
        val compiled = """
            {"outbounds":[
              {"type":"vless","tag":"proxy","server":"151.1.2.3","server_port":443},
              {"type":"direct","tag":"direct"}
            ]}
        """.trimIndent()
        assertThat(OutboundEndpoint.fromAssembled(compiled, "proxy"))
            .isEqualTo(OutboundEndpoint("151.1.2.3", 443))
    }

    @Test
    fun rejectsMissingPort() {
        assertThat(OutboundEndpoint.parse("""{"type":"vless","server":"151.1.2.3"}""")).isNull()
    }
}
