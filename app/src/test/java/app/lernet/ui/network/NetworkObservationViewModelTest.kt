package app.lernet.ui.network

import android.app.Application
import androidx.lifecycle.ViewModelStore
import app.lernet.engine.net.observation.NetworkSnapshot
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Fake connection and passive snapshot only; never starts LerNET or reaches a real network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NetworkObservationViewModelTest {
    @Test fun refreshingCancelsTheOldIpRequestAndCannotAttachItsResultToTheNewSnapshot() {
        val connection = FakeConnection()
        val sequence = AtomicInteger()
        val vm = NetworkObservationViewModel(RuntimeEnvironment.getApplication(), {
            NetworkSnapshot(id = "fake-${sequence.incrementAndGet()}", platform = "Android", startedAt = 1)
        }, { connection })
        val store = ViewModelStore().apply { put("test", vm) }
        try {
            await { !vm.state.value.collecting }
            vm.checkExternalIp()
            await { connection.started.count == 0L }
            vm.refresh()
            await { !vm.state.value.collecting && !vm.state.value.checkingIp }
            assertThat(connection.disconnects.get()).isAtLeast(1)
            assertThat(vm.state.value.snapshot?.id).isEqualTo("fake-2")
            assertThat(vm.state.value.externalIp).isNull()
            assertThat(vm.state.value.ipError).isNull()
            assertThat(connection.useCaches).isFalse()
            assertThat(connection.instanceFollowRedirects).isFalse()
        } finally { store.clear(); connection.release.countDown() }
    }

    @Test fun clearingWhileTheFactoryIsReturningCannotStartAnOrphanRequest() {
        val opening = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val connection = FakeConnection()
        val vm = NetworkObservationViewModel(RuntimeEnvironment.getApplication(), {
            NetworkSnapshot(platform = "Android", startedAt = 1)
        }, { opening.countDown(); check(allowReturn.await(3, TimeUnit.SECONDS)); connection })
        val store = ViewModelStore().apply { put("test", vm) }
        try {
            await { !vm.state.value.collecting }
            vm.checkExternalIp()
            await { opening.count == 0L }
            store.clear()
            allowReturn.countDown()
            await { connection.disconnects.get() > 0 }
            assertThat(connection.started.count).isEqualTo(1L)
            assertThat(vm.state.value.externalIp).isNull()
        } finally { allowReturn.countDown(); store.clear(); connection.release.countDown() }
    }

    @Test fun unexpectedRefreshFailureKeepsTheLastSnapshotAndDoesNotEscapeTheUiCoroutine() {
        val sequence = AtomicInteger()
        val vm = NetworkObservationViewModel(RuntimeEnvironment.getApplication(), {
            if (sequence.incrementAndGet() > 1) error("private details must not leak into UI")
            NetworkSnapshot(id = "kept", platform = "Android", startedAt = 1)
        }, { error("IP check was not requested") })
        val store = ViewModelStore().apply { put("test", vm) }
        try {
            await { !vm.state.value.collecting }
            vm.refresh()
            await { !vm.state.value.collecting }
            assertThat(vm.state.value.snapshot?.id).isEqualTo("kept")
            assertThat(vm.state.value.previous).isNull()
            assertThat(vm.state.value.notice).contains("Снимок не обновлён")
            assertThat(vm.state.value.notice).doesNotContain("private details")
        } finally { store.clear() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertThat(condition()).isTrue()
    }

    private class FakeConnection : HttpURLConnection(URL("https://example.invalid")) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disconnects = AtomicInteger()
        override fun getResponseCode(): Int {
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            return 200
        }
        override fun getInputStream() = ByteArrayInputStream("198.51.100.19".toByteArray())
        override fun disconnect() { disconnects.incrementAndGet(); release.countDown() }
        override fun usingProxy() = false
        override fun connect() = Unit
    }
}
