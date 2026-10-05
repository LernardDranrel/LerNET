package app.lernet.vpn.expert

import app.lernet.engine.policy.TunIdentity

data class ExpertVpnToken(val generation: Long)

/** A reservation survives native failure until its owner has finished closing native resources. */
class ExpertVpnLease {
    private var generation = 0L
    private var token: ExpertVpnToken? = null
    private var serviceInstance: String? = null
    private var serviceAttached = false
    private var tun: TunIdentity? = null
    private var establishedOnce = false

    @get:Synchronized
    val isOwned: Boolean get() = token != null

    @get:Synchronized
    val identity: TunIdentity? get() = tun

    @Synchronized
    fun reserve(): ExpertVpnToken {
        check(token == null) { "Expert VPN session is already reserved" }
        return ExpertVpnToken(++generation).also { token = it }
    }

    @Synchronized
    fun attach(expected: ExpertVpnToken, instance: String) {
        require(instance.isNotBlank()) { "VPN service instance is blank" }
        check(token == expected) { "Expert VPN session is stale" }
        check(serviceInstance == null || serviceInstance == instance) { "VPN service changed during Expert session" }
        check(serviceInstance == null || serviceAttached) { "Expert VPN service has already closed" }
        serviceInstance = instance
        serviceAttached = true
    }

    @Synchronized
    fun ownsService(expected: ExpertVpnToken, instance: String): Boolean =
        token == expected && serviceAttached && serviceInstance == instance

    @Synchronized
    fun acceptsServiceStart(expected: ExpertVpnToken, instance: String): Boolean =
        token == expected && (serviceInstance == null || (serviceAttached && serviceInstance == instance))

    @Synchronized
    fun ownsService(instance: String): Boolean =
        token != null && serviceAttached && serviceInstance == instance

    /** Keep the generation fenced while the service closes its original descriptor. */
    @Synchronized
    fun closeService(expected: ExpertVpnToken, instance: String, closeOriginal: () -> Unit) {
        check(ownsService(expected, instance)) { "VPN service does not own the Expert reservation" }
        closeOriginal()
        serviceDetached(instance)
    }

    @Synchronized
    fun serviceCloseConfirmed(expected: ExpertVpnToken): Boolean =
        token == expected && !serviceAttached

    @Synchronized
    fun requireFirstTun(instance: String) {
        if (token == null) return
        check(serviceAttached && serviceInstance == instance) { "VPN service does not own the Expert reservation" }
        check(!establishedOnce) { "Expert TUN must not be replaced during an active session" }
    }

    @Synchronized
    fun established(instance: String, descriptor: Int): TunIdentity? {
        if (token == null) return null
        require(descriptor >= 0) { "Invalid Expert TUN descriptor" }
        requireFirstTun(instance)
        establishedOnce = true
        return TunIdentity("android:$instance:$descriptor:${token!!.generation}").also { tun = it }
    }

    @Synchronized
    fun serviceDetached(instance: String) {
        if (serviceInstance == instance) {
            tun = null
            serviceAttached = false
        }
    }

    @Synchronized
    fun release(expected: ExpertVpnToken): Boolean {
        if (token != expected) return false
        token = null
        serviceInstance = null
        serviceAttached = false
        tun = null
        establishedOnce = false
        return true
    }
}
