package app.lernet.engine.net

import app.lernet.engine.ConnectionCause
import app.lernet.engine.EngineErrorMapper

/**
 * Android docs:
 * - [android.net.VpnService.protect] returns false if the VPN is not prepared
 *   or has been revoked. The fd stays inside the TUN → traffic loop.
 * - [android.net.VpnService.Builder.establish] returns null on revoke.
 *
 * TUN mode must throw a checked [Exception] (gomobile-safe). Logging and
 * returning is not recovery. PROXY (no TUN) may skip protect.
 */
object VpnGuard {
    const val PROTECT_FALSE =
        "android: VpnService.protect returned false (not prepared or revoked)"
    const val MISSING_VPN_TUN =
        "android: VpnService missing in TUN mode (not prepared or revoked)"
    const val ESTABLISH_NULL =
        "android: the application is not prepared or is revoked"

    fun crumbProtectFailed(fd: Int): String = "protect($fd) FAILED → Failed"

    fun crumbEstablishFailed(): String = "establish() FAILED → Failed"

    sealed class ProtectOutcome {
        data object SkipProxy : ProtectOutcome()

        data object Ok : ProtectOutcome()

        data class Fail(val message: String, val crumb: String) : ProtectOutcome()
    }

    fun decideProtect(
        tunRequired: Boolean,
        hasVpn: Boolean,
        protectOk: Boolean?,
        fd: Int,
    ): ProtectOutcome =
        when {
            !tunRequired -> ProtectOutcome.SkipProxy
            !hasVpn -> ProtectOutcome.Fail(MISSING_VPN_TUN, crumbProtectFailed(fd))
            protectOk != true -> ProtectOutcome.Fail("$PROTECT_FALSE fd=$fd", crumbProtectFailed(fd))
            else -> ProtectOutcome.Ok
        }

    fun requireProtectDecision(
        tunRequired: Boolean,
        hasVpn: Boolean,
        protectOk: Boolean?,
        fd: Int,
    ): ProtectOutcome {
        val decided = decideProtect(tunRequired, hasVpn, protectOk, fd)
        if (decided is ProtectOutcome.Fail) {
            throw Exception(decided.message)
        }
        return decided
    }

    fun requireProtect(ok: Boolean, fd: Int = -1) {
        if (ok) return
        val suffix = if (fd >= 0) " fd=$fd" else ""
        throw Exception(PROTECT_FALSE + suffix)
    }

    fun <T : Any> requireEstablished(pfd: T?): T {
        if (pfd != null) return pfd
        throw Exception(ESTABLISH_NULL)
    }

    fun isRevokeMessage(raw: String?): Boolean {
        val lower = raw?.lowercase()?.trim().orEmpty()
        if (lower.isEmpty()) return false
        return "protect returned false" in lower ||
            "protect=false" in lower ||
            "protect(" in lower &&
            "failed → failed" in lower ||
            "missing in tun" in lower ||
            ESTABLISH_NULL.lowercase() in lower ||
            "not prepared or revoked" in lower ||
            "establish() failed" in lower
    }

    fun causeOf(raw: String?): ConnectionCause =
        if (isRevokeMessage(raw)) ConnectionCause.ServiceRevoked else EngineErrorMapper.map(raw.orEmpty())
}
