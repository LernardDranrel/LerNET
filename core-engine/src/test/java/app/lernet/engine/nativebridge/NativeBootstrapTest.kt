package app.lernet.engine.nativebridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NativeBootstrapTest {
    @Test
    fun setLocaleThenSetupThenVersionNeverSetContext() {
        val seen = mutableListOf<String>()
        val version = NativeBootstrap.run(
            RecordingBridge(seen),
            onLocaleFailure = { error("locale must not fail") },
        )
        assertThat(seen).containsExactly("setLocale", "setup", "version").inOrder()
        assertThat(version).isEqualTo("1.14.1-lx.8")
        assertThat(seen).doesNotContain("setContext")
        assertThat(seen).doesNotContain("loadLibrary")
    }

    @Test
    fun localeFailureStillRunsSetup() {
        val seen = mutableListOf<String>()
        val localeErrors = mutableListOf<String>()
        val version = NativeBootstrap.run(
            RecordingBridge(seen, localeThrows = true),
            onLocaleFailure = { localeErrors += (it.message ?: "") },
        )
        assertThat(seen).containsExactly("setLocale", "setup", "version").inOrder()
        assertThat(localeErrors).containsExactly("bad locale")
        assertThat(version).isEqualTo("1.14.1-lx.8")
    }

    @Test
    fun setupNeverRunsBeforeSetLocale() {
        val seen = mutableListOf<String>()
        NativeBootstrap.run(
            RecordingBridge(seen, localeThrows = true),
            onLocaleFailure = { seen += "locale-fail" },
        )
        assertThat(seen.first()).isEqualTo("setLocale")
        assertThat(seen.indexOf("setup")).isGreaterThan(seen.indexOf("setLocale"))
    }

    @Test
    fun gomobileObjectConstructionStaysInsideSetupAfterLocale() {
        val seen = mutableListOf<String>()
        NativeBootstrap.run(
            RecordingBridge(seen, constructOptionsInSetup = true),
            onLocaleFailure = { error("locale must not fail") },
        )
        assertThat(seen).containsExactly(
            "setLocale",
            "construct-SetupOptions",
            "setup",
            "version",
        ).inOrder()
    }
}

private class RecordingBridge(
    private val seen: MutableList<String>,
    private val localeThrows: Boolean = false,
    private val constructOptionsInSetup: Boolean = false,
) : NativeBridge {
    override fun setLocale() {
        seen += "setLocale"
        if (localeThrows) {
            error("bad locale")
        }
    }

    override fun setup() {
        if (constructOptionsInSetup) {
            seen += "construct-SetupOptions"
        }
        seen += "setup"
    }

    override fun version(): String {
        seen += "version"
        return "1.14.1-lx.8"
    }
}
