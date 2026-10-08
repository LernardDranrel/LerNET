package app.lernet.vpn

import android.content.Context
import android.net.VpnService
import app.lernet.engine.ConnectionController
import app.lernet.engine.redact.LerNetLog
import app.lernet.vpn.expert.ExpertServiceRestorer
import app.lernet.vpn.expert.ExpertVpnSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object SimpleServiceRestorer {
    @Volatile private var restore: (() -> Unit)? = null
    @Volatile private var clear: (() -> Unit)? = null
    @Volatile private var clearForExpert: (() -> Unit)? = null
    fun register(restore: () -> Unit, clear: () -> Unit, clearForExpert: () -> Unit) {
        this.restore = restore; this.clear = clear; this.clearForExpert = clearForExpert
    }
    fun restore() { restore?.invoke() }
    fun clear() { clear?.invoke() }
    fun clearForExpert() { clearForExpert?.invoke() }
}

@Singleton
class SimpleSessionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SimpleSessionStore,
    private val controller: ConnectionController,
    private val scope: CoroutineScope,
) {
    private var restoreJob: Job? = null
    init {
        SimpleServiceRestorer.register(restore = ::restore, clear = { store.write(null) }, clearForExpert = store::clearForExpert)
    }

    @Synchronized private fun restore() {
        if (restoreJob?.isActive == true) return
        restoreJob = scope.launch(Dispatchers.IO) {
            try {
                val saved = store.read() ?: return@launch
                if (VpnService.prepare(context) != null || ExpertServiceRestorer.shouldRestore() || ExpertVpnSession.isOwned) return@launch
                controller.resumePersistentSession(saved.config()) {
                    store.read()?.generation == saved.generation && !ExpertServiceRestorer.shouldRestore() && !ExpertVpnSession.isOwned
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                LerNetLog.e("LerNET.Restore", "Simple system restore failed (${error.javaClass.simpleName})")
            }
        }
    }
}
