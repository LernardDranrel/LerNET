package app.lernet.engine.net

import app.lernet.engine.ConnectionCause
import app.lernet.engine.EngineErrorMapper
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AllowedAppsGuardTest {
    @Test
    fun noAllowListMeansNothingToCheck() {
        AllowedAppsGuard.requireSomeAllowed(emptyList(), emptyList())
    }

    @Test
    fun partialSuccessKeepsTheAllowList() {
        AllowedAppsGuard.requireSomeAllowed(listOf("a", "b"), listOf("b"))
    }

    @Test
    fun allFailedRefusesToCaptureEveryApp() {
        val error = assertThrows(Exception::class.java) {
            AllowedAppsGuard.requireSomeAllowed(listOf("gone.app", "old.app"), emptyList())
        }
        assertThat(error.message).contains(AllowedAppsGuard.MARKER)
    }

    @Test
    fun wrappedLibboxErrorBecomesFinalConfigErrorWithUserText() {
        val raw = "start service: start inbound/tun[tun-in]: configure tun interface: " +
            "${AllowedAppsGuard.MARKER} (requested 2)"
        val cause = EngineErrorMapper.map(raw)
        assertThat(cause).isInstanceOf(ConnectionCause.InvalidConfig::class.java)
        assertThat(cause.isRetryable()).isFalse()
        assertThat(cause.technicalDetail()).isEqualTo(AllowedAppsGuard.USER_MESSAGE)
    }
}
