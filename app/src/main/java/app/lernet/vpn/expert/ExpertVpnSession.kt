package app.lernet.vpn.expert

import app.lernet.engine.policy.TunIdentity

/** Shared by the existing VpnService and both engine entry points; never opens a second VPN. */
object ExpertVpnSession {
    private val lease = ExpertVpnLease()
    private var reservation: ExpertVpnToken? = null

    @Volatile
    private var failureSink: ((TunIdentity?, String) -> Unit)? = null

    val isOwned: Boolean get() = lease.isOwned
    val identity: TunIdentity? get() = lease.identity

    @Synchronized
    fun reserve(onFailure: (TunIdentity?, String) -> Unit): ExpertVpnToken = lease.reserve().also {
        reservation = it
        failureSink = onFailure
    }

    @Synchronized
    fun attachService(instance: String) {
        reservation?.let { lease.attach(it, instance) }
    }

    fun requireFirstTun(instance: String) = lease.requireFirstTun(instance)

    fun tunEstablished(instance: String, descriptor: Int) = lease.established(instance, descriptor)

    fun serviceDetached(instance: String) = lease.serviceDetached(instance)

    fun ownsService(expected: ExpertVpnToken, instance: String): Boolean = lease.ownsService(expected, instance)

    fun ownsService(instance: String): Boolean = lease.ownsService(instance)

    fun acceptsServiceStart(expected: ExpertVpnToken, instance: String): Boolean = lease.acceptsServiceStart(expected, instance)

    fun serviceCloseConfirmed(expected: ExpertVpnToken): Boolean = lease.serviceCloseConfirmed(expected)

    @Synchronized
    fun closeService(expected: ExpertVpnToken, instance: String, closeOriginal: () -> Unit) =
        lease.closeService(expected, instance, closeOriginal)

    @Synchronized
    fun signalFailure(instance: String, reason: String) {
        if (lease.ownsService(instance)) failureSink?.invoke(identity, reason)
    }

    @Synchronized
    fun release(token: ExpertVpnToken): Boolean {
        if (!lease.release(token)) return false
        reservation = null
        failureSink = null
        return true
    }

    const val ACTION_START = "app.lernet.action.EXPERT_START"
    const val EXTRA_GENERATION = "app.lernet.extra.EXPERT_GENERATION"
}
