package app.lernet.config.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DnsPolicyTest {
    @Test
    fun unknownStorageFallsBackToUnderlay() {
        assertThat(DnsPolicy.fromStorage(null)).isEqualTo(DnsPolicy.UNDERLAY)
        assertThat(DnsPolicy.fromStorage("nope")).isEqualTo(DnsPolicy.UNDERLAY)
        assertThat(DnsPolicy.fromStorage("PROFILE")).isEqualTo(DnsPolicy.PROFILE)
    }
}
