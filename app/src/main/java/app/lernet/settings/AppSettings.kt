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
    val defaultDnsPolicy: DnsPolicy = DnsPolicy.DEFAULT,
    val lockdownHintDismissed: Boolean = false,
    /** Android 13+: the notification permission was offered once, at the first connection. */
    val notificationsAsked: Boolean = false,
    val notificationHintDismissed: Boolean = false,
    /** Own persistent login/password for the local proxy; empty means random for every connection. */
    val proxyUsername: String = "",
    val proxyPassword: String = "",
)
