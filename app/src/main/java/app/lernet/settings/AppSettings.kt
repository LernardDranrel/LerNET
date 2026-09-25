package app.lernet.settings

import app.lernet.config.model.DnsPolicy
import app.lernet.engine.RunMode
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.policy.ReconnectSettings

data class AppSettings(
    val mode: RunMode = RunMode.FULL_VPN,
    val activeProfileId: String? = null,
    val reconnect: ReconnectSettings = ReconnectSettings(),
    val failoverEnabled: Boolean = false,
    val failoverGroupId: String? = null,
    val logLevel: String = "warn",
    val dismissedBannerKey: String? = null,
    val journalMaxMb: Int = 100,
    val engineDefaults: EngineDefaults = EngineDefaults(),
    val defaultDnsPolicy: DnsPolicy = DnsPolicy.UNDERLAY,
)
