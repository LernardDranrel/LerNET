package app.lernet.engine.nativebridge

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SFA-aligned process-wide bootstrap: setLocale → setup. Never `Seq.setContext`.
 * Concurrent Connect waits on the same attempt. Failed setup may retry setup
 * without a second setLocale.
 */
class NativeBootstrapSession {
    private val lock = ReentrantLock()
    private val done = lock.newCondition()

    @Volatile
    private var localeArmed = false

    @Volatile
    private var readyVersion: String? = null

    private var inflight = false

    var localeCalls: Int = 0
        private set

    var setupCalls: Int = 0
        private set

    val isReady: Boolean
        get() = readyVersion != null

    fun prepareLocale(
        bridge: NativeBridge,
        onLocaleFailure: (Throwable) -> Unit,
    ) {
        lock.withLock {
            if (localeArmed) {
                return
            }
            while (inflight) {
                done.await()
                if (localeArmed) {
                    return
                }
            }
            inflight = true
        }
        try {
            armLocale(bridge, onLocaleFailure)
        } finally {
            lock.withLock {
                inflight = false
                done.signalAll()
            }
        }
    }

    fun await(
        bridge: NativeBridge,
        onLocaleFailure: (Throwable) -> Unit,
    ): String {
        lock.withLock {
            readyVersion?.let { return it }
            while (inflight) {
                done.await()
                readyVersion?.let { return it }
            }
            inflight = true
        }
        return try {
            val version = execute(bridge, onLocaleFailure)
            lock.withLock {
                readyVersion = version
                inflight = false
                done.signalAll()
            }
            version
        } catch (error: Throwable) {
            lock.withLock {
                inflight = false
                done.signalAll()
            }
            throw error
        }
    }

    private fun execute(
        bridge: NativeBridge,
        onLocaleFailure: (Throwable) -> Unit,
    ): String {
        armLocale(bridge, onLocaleFailure)
        setupCalls += 1
        bridge.setup()
        return bridge.version()
    }

    private fun armLocale(
        bridge: NativeBridge,
        onLocaleFailure: (Throwable) -> Unit,
    ) {
        if (localeArmed) {
            return
        }
        runCatching { bridge.setLocale() }.onFailure(onLocaleFailure)
        localeArmed = true
        localeCalls += 1
    }
}
