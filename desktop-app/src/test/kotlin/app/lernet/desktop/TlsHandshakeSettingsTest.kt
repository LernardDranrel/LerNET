package app.lernet.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TlsHandshakeSettingsTest {
    @Test
    fun handshakeOptionsReachTheAssemblerDefaults() {
        val off = StoredState().engineDefaults()
        assertThat(off.tlsRecordFragment).isFalse()
        assertThat(off.realityKeyShare).isEmpty()
        val on = StoredState(tlsRecordFragment = true, realityKeyShare = "classical").engineDefaults()
        assertThat(on.tlsRecordFragment).isTrue()
        assertThat(on.realityKeyShare).isEqualTo("classical")
        assertThat(StoredState(realityKeyShare = "junk").engineDefaults().realityKeyShare).isEmpty()
    }

    @Test
    fun dnsGoesThroughTheTunnelByDefault() {
        assertThat(StoredState().defaultDnsPolicy).isEqualTo("TUNNEL")
    }
}
