package app.lernet.vpn.expert

/** A throwing close may consume its descriptor; a later no-op cannot prove that close succeeded. */
internal class RetainedTunDescriptor<T>(private val closeDescriptor: (T) -> Unit) {
    var descriptor: T? = null
        private set
    private var closeFailure: Throwable? = null

    fun retain(next: T) {
        check(descriptor == null && closeFailure == null) { "Previous VPN TUN closure is unconfirmed" }
        descriptor = next
    }

    fun close() {
        closeFailure?.let { throw IllegalStateException("VPN TUN closure is unconfirmed", it) }
        val current = descriptor ?: return
        try {
            closeDescriptor(current)
            descriptor = null
        } catch (failure: Throwable) {
            closeFailure = failure
            throw failure
        }
    }
}
