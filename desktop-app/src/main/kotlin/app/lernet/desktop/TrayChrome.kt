package app.lernet.desktop

import app.lernet.desktop.expert.ExpertPhase
import app.lernet.engine.text.StatusWords

/** Tray / taskbar look outside the window — one mapping for both surfaces. */
internal data class TrayChrome(val icon: String, val tooltip: String)

/**
 * Contract: expert phase (or null), expert exit/tunnel error, tunnel status, healthFailures.
 * Expert non-STOPPED owns the chrome. RUNNING never looks like OFF; healthVerified is not consulted.
 * healthFailures ≥ 2 (VPN) or expertTunnelError → failed icon.
 */
internal fun trayChrome(
    expertPhase: ExpertPhase?,
    expertTunnelError: Boolean,
    tunnel: TunnelStatus,
    healthFailures: Int,
): TrayChrome {
    if (expertPhase != null && expertPhase != ExpertPhase.STOPPED) {
        return when (expertPhase) {
            ExpertPhase.FAILED -> TrayChrome(ICON_FAILED, StatusWords.chrome(StatusWords.FAILED))
            ExpertPhase.RUNNING, ExpertPhase.APPLYING -> if (expertTunnelError) {
                TrayChrome(ICON_FAILED, StatusWords.chrome(StatusWords.NO_REPLY))
            } else {
                TrayChrome(ICON_RUNNING, StatusWords.chrome(StatusWords.EXPERT))
            }
            ExpertPhase.STARTING, ExpertPhase.STOPPING ->
                TrayChrome(ICON_RUNNING, StatusWords.chrome(StatusWords.EXPERT))
            ExpertPhase.STOPPED -> error("unreachable")
        }
    }
    return when (tunnel) {
        TunnelStatus.RUNNING -> when {
            healthFailures >= 2 -> TrayChrome(ICON_FAILED, StatusWords.chrome(StatusWords.NO_REPLY))
            else -> TrayChrome(ICON_RUNNING, StatusWords.chrome(StatusWords.CONNECTED))
        }
        TunnelStatus.STARTING -> TrayChrome(ICON_RUNNING, StatusWords.chrome(StatusWords.CONNECTING))
        TunnelStatus.RECONNECTING -> TrayChrome(ICON_RUNNING, StatusWords.chrome(StatusWords.RECONNECTING))
        TunnelStatus.FAILED -> TrayChrome(ICON_FAILED, StatusWords.chrome(StatusWords.FAILED))
        TunnelStatus.STOPPED -> TrayChrome(ICON_OFF, StatusWords.chrome(StatusWords.OFF))
    }
}

private const val ICON_OFF = "lernet-icon.png"
private const val ICON_RUNNING = "lernet-icon-running.png"
private const val ICON_FAILED = "lernet-icon-failed.png"
