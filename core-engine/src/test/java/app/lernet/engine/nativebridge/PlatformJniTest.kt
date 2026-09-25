package app.lernet.engine.nativebridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlatformJniTest {
    @Test
    fun runtimeExceptionBecomesCheckedException() {
        val thrown = runCatching { PlatformJni.call("openTun") { error("proxy mode has no TUN") } }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(Exception::class.java)
        assertThat(thrown.exceptionOrNull()!!.javaClass).isEqualTo(Exception::class.java)
        assertThat(thrown.exceptionOrNull()!!.message).contains("proxy mode has no TUN")
    }

    @Test
    fun checkedExceptionPassesThrough() {
        val original = Exception("establish failed")
        val thrown = runCatching { PlatformJni.call("openTun") { throw original } }
        assertThat(thrown.exceptionOrNull()).isSameInstanceAs(original)
    }

    @Test
    fun errorBecomesCheckedException() {
        val thrown = runCatching {
            PlatformJni.call("openTun") { throw OutOfMemoryError("native") }
        }
        assertThat(thrown.exceptionOrNull()!!.javaClass).isEqualTo(Exception::class.java)
        assertThat(thrown.exceptionOrNull()!!.message).contains("OutOfMemoryError")
    }

    @Test
    fun runSwallowsAndReports() {
        var reported: Pair<String, String>? = null
        PlatformJni.run("protect", { site, error -> reported = site to (error.message ?: "") }) {
            error("protect exploded")
        }
        assertThat(reported).isEqualTo("protect" to "protect exploded")
    }

    @Test
    fun orElseReturnsFallbackOnThrowable() {
        var reported = false
        val value = PlatformJni.orElse(emptyList<String>(), { reported = true }) {
            error("interfaces exploded")
        }
        assertThat(reported).isTrue()
        assertThat(value).isEmpty()
    }
}
