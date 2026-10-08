package app.lernet.vpn

/** Callback-owned facts. Late facts for a superseded network never select it again. */
internal class UnderlayCallbackState<N, C, L> {
    data class Facts<N, C, L>(val network: N, val capabilities: C, val link: L)
    private var selected: N? = null
    private var capabilities: C? = null
    private var link: L? = null

    fun available(network: N) {
        if (selected == network) return
        selected = network
        capabilities = null
        link = null
    }

    fun capabilities(network: N, value: C): Facts<N, C, L>? {
        if (selected != network) return null
        capabilities = value
        return complete()
    }

    fun link(network: N, value: L): Facts<N, C, L>? {
        if (selected != network) return null
        link = value
        return complete()
    }

    fun lost(network: N): Boolean {
        if (selected != network) return false
        clear()
        return true
    }

    fun clear() { selected = null; capabilities = null; link = null }

    fun snapshot(): Facts<N, C, L>? = complete()

    private fun complete(): Facts<N, C, L>? {
        val network = selected ?: return null
        val caps = capabilities ?: return null
        val properties = link ?: return null
        return Facts(network, caps, properties)
    }
}
