package app.lernet.engine

import app.lernet.config.model.DnsPolicy
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.net.OutboundEndpoint

/** Captures the effective running configuration, never a newer repository draft. Contains secrets. */
class SimpleModeRestorePoint internal constructor(
    val profileId: String,
    val outboundId: String,
    val mode: RunMode,
    internal val compiledJson: String,
    internal val controllerEpoch: Long,
    internal val endpoint: OutboundEndpoint?,
    internal val logLevel: String,
    internal val defaults: EngineDefaults,
    internal val proxyTag: String?,
    internal val dnsPolicy: DnsPolicy,
)
