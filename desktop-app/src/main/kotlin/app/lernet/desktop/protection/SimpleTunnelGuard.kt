package app.lernet.desktop.protection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the user is told about the simple-mode guard. Nothing is shown while it simply works. */
sealed interface SimpleGuardStatus {
    data object Off : SimpleGuardStatus
    data object Closed : SimpleGuardStatus
    data object Active : SimpleGuardStatus
    /** [reason] goes to the journal; [networkClosed] means the internet stays closed until Stop. */
    data class Unavailable(val reason: String, val networkClosed: Boolean = false) : SimpleGuardStatus
}

/**
 * Keeps Windows closed outside the LerNET adapter while a simple-mode VPN session is wanted,
 * including the seconds in which sing-box restarts. Owned by the GUI through a dynamic WFP session:
 * Stop, exit or a GUI crash remove every filter, so normal connectivity always returns.
 */
class SimpleTunnelGuard internal constructor(
    private val openSession: () -> WfpSession,
    private val adapterLuid: () -> Long?,
    private val dhcpServicePath: () -> String?,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val adapterWaitMs: Long = 10_000,
) {
    constructor() : this(
        openSession = { JnaWfpSession.openDynamic() },
        adapterLuid = { WindowsAdapterLuid.byAlias(app.lernet.engine.compile.ConfigAssembler.WINDOWS_TUN_INTERFACE) },
        dhcpServicePath = { (System.getenv("SystemRoot") ?: "C:\\Windows") + "\\System32\\svchost.exe" },
    )

    private val mutable = MutableStateFlow<SimpleGuardStatus>(SimpleGuardStatus.Off)
    val status: StateFlow<SimpleGuardStatus> = mutable

    private var session: WfpSession? = null
    private var corePath: String? = null
    private var tunLuid: Long? = null

    /** Closes everything but the core before it starts. Repeated calls keep the same session. */
    @Synchronized
    fun engage(core: String): SimpleGuardStatus {
        if (session != null && corePath.equals(core, ignoreCase = true)) return mutable.value
        return runCatching {
            val active = session ?: openSession().also { session = it }
            apply(active, core, tunLuid)
            corePath = core
            SimpleGuardStatus.Closed
        }.getOrElse { failure ->
            closeQuietly()
            SimpleGuardStatus.Unavailable(failure.message ?: failure.javaClass.simpleName)
        }.also { mutable.value = it }
    }

    /** Lets traffic into the adapter the core has just created. Called after every (re)start. */
    fun permitAdapter(): SimpleGuardStatus {
        val engagedSession = synchronized(this) { session } ?: return mutable.value
        // Wait for the adapter without holding the lock: Stop must never wait behind this.
        var luid: Long? = null
        var waited = 0L
        while (luid == null && waited <= adapterWaitMs && synchronized(this) { session === engagedSession }) {
            luid = runCatching { adapterLuid() }.getOrNull()?.takeIf { it != 0L }
            if (luid == null) {
                sleep(ADAPTER_POLL_MS)
                waited += ADAPTER_POLL_MS
            }
        }
        synchronized(this) {
            val active = session
            val core = corePath
            if (active == null || active !== engagedSession || core == null) return mutable.value
            if (luid == null) {
                // Still closed: better no network than traffic around the VPN.
                return SimpleGuardStatus.Unavailable("не найден адаптер LerNET, доступ в сеть закрыт до перезапуска", networkClosed = true)
                    .also { mutable.value = it }
            }
            if (luid == tunLuid && mutable.value == SimpleGuardStatus.Active) return mutable.value
            return runCatching {
                apply(active, core, luid)
                tunLuid = luid
                SimpleGuardStatus.Active
            }.getOrElse { failure ->
                SimpleGuardStatus.Unavailable(failure.message ?: failure.javaClass.simpleName)
            }.also { mutable.value = it }
        }
    }

    /** User stop, mode change, terminal failure: everything is removed at once. */
    @Synchronized
    fun release() {
        closeQuietly()
        mutable.value = SimpleGuardStatus.Off
    }

    @Synchronized
    fun isEngaged(): Boolean = session != null

    private fun apply(active: WfpSession, core: String, luid: Long?) {
        val wanted = SimpleGuardPlan.filters(core, luid, dhcpServicePath())
        active.transaction {
            active.ensureOwnership()
            val wantedKeys = wanted.map { it.key }.toSet()
            val present = active.filters()
            present.filter { it.key !in wantedKeys }.forEach { active.delete(it.key) }
            val presentKeys = present.map { it.key }.toSet()
            wanted.filter { it.key !in presentKeys }.forEach(active::add)
        }
    }

    private fun closeQuietly() {
        val active = session
        session = null
        corePath = null
        tunLuid = null
        runCatching { active?.close() }
    }

    private companion object {
        const val ADAPTER_POLL_MS = 250L
    }
}
