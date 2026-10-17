package app.lernet.engine.policy

import app.lernet.engine.RunMode

/**
 * Decides whether a failed simple-mode (re)start may leave the Android VPN interface in place.
 *
 * While the interface exists and nobody reads it, apps cannot reach the network around the
 * tunnel. Only the controller's terminal decisions (user stop, revoke, final failure) remove it.
 */
object TunHoldPolicy {
    fun holdOnStartFailure(mode: RunMode, stopRequested: Boolean, vpnServiceUp: Boolean): Boolean =
        mode == RunMode.FULL_VPN && !stopRequested && vpnServiceUp
}
