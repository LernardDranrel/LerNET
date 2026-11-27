package app.lernet.ui.home

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.lernet.R
import app.lernet.engine.ConnectionState
import app.lernet.ui.controls.ConnectionHeader
import app.lernet.ui.theme.LerNetHold
import app.lernet.ui.theme.LerNetOk

@Composable
internal fun ConnectionHero(
    connection: ConnectionState,
    status: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    holding: Boolean = false,
    showAction: Boolean = true,
) {
    val active = isSessionActive(connection)
    val scheme = MaterialTheme.colorScheme
    val tone = if (holding) LerNetHold else when (connection) {
        ConnectionState.CONNECTED -> LerNetOk
        ConnectionState.FAILED -> scheme.error
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> scheme.primary
        ConnectionState.DISCONNECTED -> scheme.onSurfaceVariant
    }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    ConnectionHeader(
        status = status,
        tone = tone,
        active = active,
        actionLabel = if (showAction) stringResource(if (active) R.string.disconnect else R.string.connect) else null,
        enabled = enabled,
        detail = if (enabled) null else stringResource(R.string.home_choose_before_connect),
        onToggle = {
            val now = SystemClock.uptimeMillis()
            if (now >= lockedUntil) {
                lockedUntil = now + 320
                onToggle()
            }
        },
    )
}
