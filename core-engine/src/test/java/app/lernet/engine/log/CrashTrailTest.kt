package app.lernet.engine.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashTrailTest {
    @Test
    fun markInvokesPersistSink() {
        val seen = mutableListOf<String>()
        CrashTrail.persistCrumb = CrashTrailSink { seen += it }
        CrashTrail.persistFailure = null
        try {
            CrashTrail.mark("before Libbox.setup")
            assertThat(seen).containsExactly("before Libbox.setup")
        } finally {
            CrashTrail.persistCrumb = null
        }
    }

    @Test
    fun recordFailureWritesSiteAndThrowable() {
        val crumbs = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()
        CrashTrail.persistCrumb = CrashTrailSink { crumbs += it }
        CrashTrail.persistFailure = CrashFailureSink { site, error ->
            failures += site to (error.message ?: "")
        }
        try {
            CrashTrail.recordFailure("Libbox.setup", IllegalStateException("dlopen failed"))
            assertThat(crumbs.single()).contains("FAILURE Libbox.setup")
            assertThat(failures).containsExactly("Libbox.setup" to "dlopen failed")
        } finally {
            CrashTrail.persistCrumb = null
            CrashTrail.persistFailure = null
        }
    }
}
