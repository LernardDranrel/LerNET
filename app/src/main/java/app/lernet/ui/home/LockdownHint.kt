package app.lernet.ui.home

import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.RunMode
import app.lernet.vpn.expert.AndroidVpnLockdown

/**
 * One-time advice for the Android system switch that keeps traffic closed even if the app process
 * itself dies. Hidden once dismissed or once Android confirms the switch is on.
 */
internal object LockdownHint {
    fun visible(snapshot: ConnectionSnapshot, dismissed: Boolean, lockdown: AndroidVpnLockdown?): Boolean =
        !dismissed &&
            snapshot.state == ConnectionState.CONNECTED &&
            snapshot.mode == RunMode.FULL_VPN &&
            lockdown != null &&
            lockdown != AndroidVpnLockdown.ENABLED
}
