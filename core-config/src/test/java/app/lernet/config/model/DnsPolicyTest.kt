package app.lernet.config.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DnsPolicyTest {
    @Test
    fun unknownStorageFallsBackToTunnel() {
        assertThat(DnsPolicy.fromStorage(null)).isEqualTo(DnsPolicy.TUNNEL)
        assertThat(DnsPolicy.fromStorage("nope")).isEqualTo(DnsPolicy.TUNNEL)
        assertThat(DnsPolicy.fromStorage("UNDERLAY")).isEqualTo(DnsPolicy.UNDERLAY)
        assertThat(DnsPolicy.fromStorage("PROFILE")).isEqualTo(DnsPolicy.PROFILE)
    }
}
