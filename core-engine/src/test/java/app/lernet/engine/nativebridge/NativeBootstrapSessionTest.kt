package app.lernet.engine.nativebridge

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class NativeBootstrapSessionTest {
    @Test
    fun concurrentAwaitRunsLocaleAndSetupOnce() {
        val session = NativeBootstrapSession()
        val enteredSetup = CountDownLatch(1)
        val releaseSetup = CountDownLatch(1)
        val seen = mutableListOf<String>()
        val bridge = LatchBridge(seen, enteredSetup, releaseSetup)
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        val versions = mutableListOf<String>()
        try {
            val first = pool.submit(
                Callable {
                    barrier.await()
                    session.await(bridge) { error("locale must not fail") }
                },
            )
            val second = pool.submit(
                Callable {
                    barrier.await()
                    session.await(bridge) { error("locale must not fail") }
                },
            )
            assertThat(enteredSetup.await(2, TimeUnit.SECONDS)).isTrue()
            releaseSetup.countDown()
            versions.add(first.get(2, TimeUnit.SECONDS))
            versions.add(second.get(2, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertThat(versions).containsExactly("1.14.1-lx.8", "1.14.1-lx.8")
        assertThat(session.localeCalls).isEqualTo(1)
        assertThat(session.setupCalls).isEqualTo(1)
        assertThat(session.isReady).isTrue()
        assertThat(seen.count { it == "setLocale" }).isEqualTo(1)
        assertThat(seen.count { it == "setup" }).isEqualTo(1)
        assertThat(seen).doesNotContain("setContext")
        assertThat(seen).doesNotContain("loadLibrary")
    }

    @Test
    fun failedSetupRetriesWithoutSecondSetLocale() {
        val session = NativeBootstrapSession()
        val seen = mutableListOf<String>()
        val fails = AtomicInteger(1)
        val first = runCatching {
            session.await(FlakySetupBridge(seen, fails)) { }
        }
        assertThat(first.isFailure).isTrue()
        assertThat(session.localeCalls).isEqualTo(1)
        assertThat(session.isReady).isFalse()

        val version = session.await(FlakySetupBridge(seen, fails)) { }
        assertThat(version).isEqualTo("1.14.1-lx.8")
        assertThat(session.localeCalls).isEqualTo(1)
        assertThat(session.setupCalls).isEqualTo(2)
        assertThat(seen.count { it == "setLocale" }).isEqualTo(1)
        assertThat(seen.count { it == "setup" }).isEqualTo(2)
    }

    @Test
    fun prepareLocaleThenAwaitDoesNotSetLocaleTwice() {
        val session = NativeBootstrapSession()
        val seen = mutableListOf<String>()
        session.prepareLocale(RecordingSessionBridge(seen)) { error("locale must not fail") }
        assertThat(seen).containsExactly("setLocale")
        val version = session.await(RecordingSessionBridge(seen)) { error("must not setLocale again") }
        assertThat(version).isEqualTo("1.14.1-lx.8")
        assertThat(session.localeCalls).isEqualTo(1)
        assertThat(session.setupCalls).isEqualTo(1)
        assertThat(seen).containsExactly("setLocale", "setup", "version").inOrder()
    }

    @Test
    fun readyGateReturnsCachedVersionWithoutTouchingBridge() {
        val session = NativeBootstrapSession()
        val firstSeen = mutableListOf<String>()
        session.await(RecordingSessionBridge(firstSeen)) { }
        val secondSeen = mutableListOf<String>()
        val version = session.await(RecordingSessionBridge(secondSeen)) { error("must not run") }
        assertThat(version).isEqualTo("1.14.1-lx.8")
        assertThat(secondSeen).isEmpty()
        assertThat(session.localeCalls).isEqualTo(1)
        assertThat(session.setupCalls).isEqualTo(1)
    }
}

private class LatchBridge(
    private val seen: MutableList<String>,
    private val enteredSetup: CountDownLatch,
    private val releaseSetup: CountDownLatch,
) : NativeBridge {
    override fun setLocale() {
        synchronized(seen) { seen += "setLocale" }
    }

    override fun setup() {
        synchronized(seen) { seen += "setup" }
        enteredSetup.countDown()
        check(releaseSetup.await(2, TimeUnit.SECONDS))
    }

    override fun version(): String = "1.14.1-lx.8"
}

private class FlakySetupBridge(
    private val seen: MutableList<String>,
    private val remainingFailures: AtomicInteger,
) : NativeBridge {
    override fun setLocale() {
        seen += "setLocale"
    }

    override fun setup() {
        seen += "setup"
        if (remainingFailures.getAndDecrement() > 0) {
            error("setup exploded")
        }
    }

    override fun version(): String = "1.14.1-lx.8"
}

private class RecordingSessionBridge(
    private val seen: MutableList<String>,
) : NativeBridge {
    override fun setLocale() {
        seen += "setLocale"
    }

    override fun setup() {
        seen += "setup"
    }

    override fun version(): String {
        seen += "version"
        return "1.14.1-lx.8"
    }
}
