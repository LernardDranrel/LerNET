package app.lernet.engine.policy

import app.lernet.routing.policy.PolicyTarget

enum class ExpertTunnelHealth { OFF, PENDING, HEALTHY, ERROR }

/** Current engine evidence only: draft validation and historical flow failures are not tunnel health. */
val ExpertRuntimeState.tunnelHealth: ExpertTunnelHealth
    get() = when {
        phase == ExpertSessionPhase.FAILED || retiredCleanupFailures.isNotEmpty() -> ExpertTunnelHealth.ERROR
        phase == ExpertSessionPhase.STOPPED -> ExpertTunnelHealth.OFF
        phase != ExpertSessionPhase.RUNNING -> ExpertTunnelHealth.PENDING
        networkReason != null && networkRecovering -> ExpertTunnelHealth.PENDING
        networkReason != null -> ExpertTunnelHealth.ERROR
        exits.any { it.phase == ExitPhase.FAILED || it.phase == ExitPhase.DEGRADED } ->
            ExpertTunnelHealth.ERROR
        tun == null || appliedRevision == null || applying -> ExpertTunnelHealth.PENDING
        appliedPolicy?.device?.defaultTarget == PolicyTarget.Direct &&
            directNetwork?.ipv4 == DirectFamilyAvailability.UNAVAILABLE &&
            directNetwork?.ipv6 == DirectFamilyAvailability.UNAVAILABLE -> ExpertTunnelHealth.ERROR
        else -> ExpertTunnelHealth.HEALTHY
    }
