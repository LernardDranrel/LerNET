package app.lernet.engine.policy

import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyTarget

/** Only an untouched device path can adopt the user's explicitly selected Simple VPN. */
object ExpertVpnHandover {
    fun available(saved: NetworkPolicy, draft: NetworkPolicy): Boolean =
        saved == draft && saved.device.nodes.isEmpty() && saved.device.defaultTarget == PolicyTarget.Direct
}
