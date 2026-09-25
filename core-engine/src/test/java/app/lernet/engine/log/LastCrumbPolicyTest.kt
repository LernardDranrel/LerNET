package app.lernet.engine.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LastCrumbPolicyTest {
    @Test
    fun uncleanBootKeepsDeathCrumbThroughWarmup() {
        var frozen = true
        val death = "openTun snapshot before httpProxyServer"
        assertThat(LastCrumbPolicy.overwriteLastFile(frozen, death)).isFalse()
        frozen = LastCrumbPolicy.nextFrozen(frozen, death)
        assertThat(LastCrumbPolicy.overwriteLastFile(frozen, "native: before Libbox.version")).isFalse()
        frozen = LastCrumbPolicy.nextFrozen(frozen, "native: before Libbox.version")
        assertThat(frozen).isTrue()
    }

    @Test
    fun connectTapUnfreezesAndWrites() {
        assertThat(LastCrumbPolicy.overwriteLastFile(true, LastCrumbPolicy.CONNECT_TAP)).isTrue()
        assertThat(LastCrumbPolicy.nextFrozen(true, LastCrumbPolicy.CONNECT_TAP)).isFalse()
        assertThat(LastCrumbPolicy.overwriteLastFile(false, "before checkConfig")).isTrue()
    }

    @Test
    fun cleanStopUnfreezes() {
        assertThat(LastCrumbPolicy.releasesFreeze(LastCrumbPolicy.CLEAN_MARKER)).isTrue()
        assertThat(LastCrumbPolicy.nextFrozen(true, LastCrumbPolicy.CLEAN_MARKER)).isFalse()
    }
}
