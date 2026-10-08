package app.lernet.engine

import app.lernet.config.model.DnsPolicy
import app.lernet.engine.compile.EngineDefaults

/** Effective session for Android's system restart. Private storage only; JSON contains credentials. */
data class SimpleSessionConfig(
    val profileId: String,
    val outboundId: String,
    val compiledJson: String,
    val mode: RunMode,
    val logLevel: String,
    val defaults: EngineDefaults,
    val proxyTag: String?,
    val dnsPolicy: DnsPolicy,
)
