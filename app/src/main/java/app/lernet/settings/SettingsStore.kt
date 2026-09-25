package app.lernet.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.lernet.config.model.DnsPolicy
import app.lernet.engine.RunMode
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.log.JournalCeiling
import app.lernet.engine.policy.ReconnectSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "lernet_settings")

class SettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun setMode(mode: RunMode) = edit { it[MODE] = mode.name }

    suspend fun setActiveProfile(id: String?) = edit {
        if (id == null) it.remove(ACTIVE_PROFILE) else it[ACTIVE_PROFILE] = id
    }

    suspend fun setReconnect(settings: ReconnectSettings) = edit {
        it[MAX_ATTEMPTS] = settings.maxAttempts
        it[BACKOFF_INITIAL] = settings.initialBackoffMs
        it[BACKOFF_CAP] = settings.backoffCapMs
        it[WATCHDOG] = settings.watchdogTimeoutMs
    }

    suspend fun setFailover(enabled: Boolean, groupId: String?) = edit {
        it[FAILOVER] = enabled
        if (groupId == null) it.remove(FAILOVER_GROUP) else it[FAILOVER_GROUP] = groupId
    }

    suspend fun setLogLevel(level: String) = edit { it[LOG_LEVEL] = level }

    suspend fun setEngineDefaults(defaults: EngineDefaults) = edit {
        it[TUN_MTU] = defaults.tunMtu.coerceIn(1280, 9000)
        it[XMUX_CONCURRENCY] = defaults.xmuxConcurrency
        it[DIRECT_DNS_SERVER] = defaults.directDnsServer
    }

    suspend fun setDefaultDnsPolicy(policy: DnsPolicy) = edit { it[DEFAULT_DNS_POLICY] = policy.name }

    suspend fun setJournalMaxMb(mb: Int) = edit { it[JOURNAL_MB] = JournalCeiling.mb(mb) }

    suspend fun dismissBanner(key: String) = edit { it[BANNER] = key }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun Preferences.toSettings(): AppSettings {
        val modeRaw = this[MODE]
        val mode = RunMode.entries.firstOrNull { it.name == modeRaw } ?: RunMode.FULL_VPN
        return AppSettings(
            mode = mode,
            activeProfileId = this[ACTIVE_PROFILE],
            reconnect = ReconnectSettings(
                maxAttempts = this[MAX_ATTEMPTS] ?: 5,
                initialBackoffMs = this[BACKOFF_INITIAL] ?: 1_000L,
                backoffCapMs = this[BACKOFF_CAP] ?: 30_000L,
                watchdogTimeoutMs = this[WATCHDOG] ?: 20_000L,
            ),
            failoverEnabled = this[FAILOVER] ?: false,
            failoverGroupId = this[FAILOVER_GROUP],
            logLevel = this[LOG_LEVEL] ?: "warn",
            dismissedBannerKey = this[BANNER],
            journalMaxMb = JournalCeiling.mb(this[JOURNAL_MB] ?: JournalCeiling.DEFAULT_MB),
            engineDefaults = EngineDefaults(
                tunMtu = (this[TUN_MTU] ?: 1500).coerceIn(1280, 9000),
                xmuxConcurrency = this[XMUX_CONCURRENCY]?.takeIf { it in setOf("1-1", "8-8", "16-16", "32-32") } ?: "16-16",
                directDnsServer = this[DIRECT_DNS_SERVER]?.takeIf { EngineDefaults.validIpv4(it) } ?: "1.1.1.1",
            ),
            defaultDnsPolicy = DnsPolicy.fromStorage(this[DEFAULT_DNS_POLICY] ?: DnsPolicy.UNDERLAY.name),
        )
    }

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val MAX_ATTEMPTS = intPreferencesKey("max_attempts")
        val BACKOFF_INITIAL = longPreferencesKey("backoff_initial")
        val BACKOFF_CAP = longPreferencesKey("backoff_cap")
        val WATCHDOG = longPreferencesKey("watchdog")
        val FAILOVER = booleanPreferencesKey("failover")
        val FAILOVER_GROUP = stringPreferencesKey("failover_group")
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val BANNER = stringPreferencesKey("banner")
        val JOURNAL_MB = intPreferencesKey("journal_mb")
        val TUN_MTU = intPreferencesKey("tun_mtu")
        val XMUX_CONCURRENCY = stringPreferencesKey("xmux_concurrency")
        val DEFAULT_DNS_POLICY = stringPreferencesKey("default_dns_policy")
        val DIRECT_DNS_SERVER = stringPreferencesKey("direct_dns_server")
    }
}
