package app.lernet.desktop.protection

/** Identity of the exact elevated native process, never the GUI. */
data class ProtectionLease(val pid: Long, val startedAtMillis: Long) {
    init {
        require(pid in 1..0xffffffffL && startedAtMillis > 0)
    }
}

data class ProtectionTunOwner(
    val pid: Long,
    val startedAtMillis: Long,
    val tunName: String,
    val luid: Long,
    val ifIndex: Int,
    val guid: String,
)

interface ProtectionGuardian {
    fun prepare(corePath: String, tunName: String, lease: ProtectionLease): ProtectionTunOwner
    fun arm(filters: List<ProtectionFilter>, lease: ProtectionLease)

    /** Retain the creator until native route cleanup has finished. */
    fun revoke()

    /** Reject a live native lease; confirm creator cleanup before explicit policy recovery. */
    fun releaseStopped()
    fun activeKeys(): Set<String>
}
