package app.lernet.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.lernet.engine.redact.LerNetLog
import app.lernet.vpn.expert.ExpertServiceRestorer

/** «Включать VPN после перезагрузки»: on by default, like «Запускать при входе в систему» on Windows. */
object BootRestorePreference {
    private const val FILE = "lernet_boot"
    private const val KEY = "restore_on_boot"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}

/**
 * After the phone restarts, bring back the VPN the user left on (a session is saved only while the VPN is on;
 * «Отключить» clears it). Nothing happens without VPN permission or when the setting is off.
 * The system «Постоянный VPN» does the same on its own and also blocks traffic until LerNET is up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val decision = BootRestoreDecision.decide(
            enabled = BootRestorePreference.enabled(context),
            permitted = runCatching { VpnService.prepare(context) == null }.getOrDefault(false),
            expertWanted = ExpertServiceRestorer.shouldRestore(),
        )
        LerNetLog.i("LerNET.Boot", "boot restore: $decision")
        when (decision) {
            BootRestoreDecision.EXPERT -> ExpertServiceRestorer.restore()
            BootRestoreDecision.SIMPLE -> SimpleServiceRestorer.restore(offerIfEmpty = false)
            BootRestoreDecision.OFF, BootRestoreDecision.NO_PERMISSION -> Unit
        }
    }
}

enum class BootRestoreDecision {
    OFF,
    NO_PERMISSION,
    EXPERT,
    SIMPLE;

    companion object {
        fun decide(enabled: Boolean, permitted: Boolean, expertWanted: Boolean): BootRestoreDecision = when {
            !enabled -> OFF
            !permitted -> NO_PERMISSION
            expertWanted -> EXPERT
            else -> SIMPLE
        }
    }
}
