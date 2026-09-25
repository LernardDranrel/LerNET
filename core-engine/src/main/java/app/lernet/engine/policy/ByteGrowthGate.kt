package app.lernet.engine.policy

/** Lifetime TUN totals must not keep a dead session looking alive. */
class ByteGrowthGate {
    private var lastUp: Long = 0L
    private var lastDown: Long = 0L

    fun reset() {
        lastUp = 0L
        lastDown = 0L
    }

    fun observed(
        uplinkBps: Long,
        downlinkBps: Long,
        uplinkTotal: Long,
        downlinkTotal: Long,
    ): Boolean {
        val rate = uplinkBps > 0L || downlinkBps > 0L
        val grew = uplinkTotal > lastUp || downlinkTotal > lastDown
        lastUp = uplinkTotal
        lastDown = downlinkTotal
        return rate || grew
    }
}
