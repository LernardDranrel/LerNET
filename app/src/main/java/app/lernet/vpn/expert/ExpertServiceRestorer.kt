package app.lernet.vpn.expert

/** Application coordinator supplies durable intent; the service never imports UI classes. */
object ExpertServiceRestorer {
    @Volatile
    private var callbacks: Callbacks? = null

    fun register(isDesired: () -> Boolean, restore: () -> Unit, revoke: () -> Unit) {
        callbacks = Callbacks(isDesired, restore, revoke)
    }

    fun shouldRestore(): Boolean = callbacks?.isDesired?.invoke() == true

    fun restore() {
        callbacks?.restore?.invoke()
    }

    fun revoked() {
        callbacks?.revoke?.invoke()
    }

    private data class Callbacks(val isDesired: () -> Boolean, val restore: () -> Unit, val revoke: () -> Unit)
}
