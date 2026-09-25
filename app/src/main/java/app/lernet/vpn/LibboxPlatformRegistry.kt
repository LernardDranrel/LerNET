package app.lernet.vpn

import io.nekohasekai.libbox.PlatformInterface
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

object LibboxPlatformRegistry {
    private val current = AtomicReference<PlatformInterface?>(null)

    fun register(platform: PlatformInterface) {
        current.set(platform)
    }

    fun unregister(platform: PlatformInterface) {
        current.compareAndSet(platform, null)
    }

    fun peek(): PlatformInterface? = current.get()

    suspend fun await(timeoutMs: Long = 8_000L): PlatformInterface {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = current.get()
            if (ready != null) return ready
            delay(50)
        }
        error("VPN/proxy host did not register platform interface")
    }
}
