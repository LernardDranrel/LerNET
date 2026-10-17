package app.lernet.engine.policy

import app.lernet.config.net.AppTraffic
import app.lernet.engine.ConnectionState
import app.lernet.engine.compile.LocalListeners

/** Maps the connection state to where LerNET's own requests may go. Pure, for tests. */
object AppTrafficRoute {
    fun of(state: ConnectionState, compiledJson: String?): AppTraffic.Route = when (state) {
        ConnectionState.DISCONNECTED, ConnectionState.FAILED -> AppTraffic.Route.Direct
        // Without the internal listener (e.g. a session saved by an older version) stay closed.
        ConnectionState.CONNECTED -> LocalListeners.appProxy(compiledJson)?.let(AppTraffic.Route::Through)
            ?: AppTraffic.Route.Closed
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> AppTraffic.Route.Closed
    }
}
