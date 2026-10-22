package app.lernet.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.engine.policy.LinkHold

internal enum class StripTone { OK, WAIT, BAD, OFF }

internal data class StripStatus(val text: String, val tone: StripTone)

/**
 * Audit G1 (mockup m5): one line «● Подключено · Нидерланды · DNS работает» on every secondary screen,
 * so the user always sees whether the VPN is on. Expert, when its own scheme is running, wins.
 */
@Composable
internal fun stripStatus(snapshot: ConnectionSnapshot, profileName: String?, expert: ExpertRuntimeState?): StripStatus {
    when (expert?.phase) {
        ExpertSessionPhase.RUNNING -> {
            val count = expert.connections.count { it.active == true }
            return StripStatus(stringResource(R.string.status_expert_running,
                pluralStringResource(R.plurals.connections_count, count, count)), StripTone.OK)
        }
        ExpertSessionPhase.STARTING -> return StripStatus(stringResource(R.string.status_expert_starting), StripTone.WAIT)
        ExpertSessionPhase.STOPPING -> return StripStatus(stringResource(R.string.status_expert_stopping), StripTone.WAIT)
        ExpertSessionPhase.FAILED -> return StripStatus(stringResource(R.string.status_expert_failed), StripTone.BAD)
        else -> Unit
    }
    return when (snapshot.state) {
        ConnectionState.CONNECTED -> when (snapshot.hold) {
            LinkHold.JAMMED -> StripStatus(stringResource(R.string.status_jammed), StripTone.WAIT)
            LinkHold.ALL_SILENT -> StripStatus(stringResource(R.string.status_hold), StripTone.WAIT)
            else -> {
                val base = profileName?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.status_connected_profile, it) } ?: stringResource(R.string.status_connected)
                StripStatus(if (snapshot.dnsOk) "$base · ${stringResource(R.string.status_dns_ok)}" else base, StripTone.OK)
            }
        }
        ConnectionState.CONNECTING -> StripStatus(stringResource(R.string.status_connecting), StripTone.WAIT)
        ConnectionState.RECONNECTING -> StripStatus(stringResource(R.string.status_reconnecting), StripTone.WAIT)
        ConnectionState.FAILED -> StripStatus(stringResource(R.string.status_failed), StripTone.BAD)
        ConnectionState.DISCONNECTED -> StripStatus(stringResource(R.string.status_off), StripTone.OFF)
    }
}

/** Draws under the system status bar; the screen below must not pad for the status bar again. */
@Composable
internal fun StatusStrip(status: StripStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (dot, background) = when (status.tone) {
        StripTone.OK -> Color(0xFF3DDC84) to Color(0xFF3DDC84).copy(alpha = .14f)
        StripTone.WAIT -> scheme.tertiary to scheme.tertiary.copy(alpha = .14f)
        StripTone.BAD -> scheme.error to scheme.error.copy(alpha = .14f)
        StripTone.OFF -> scheme.onSurfaceVariant to scheme.surfaceVariant
    }
    Box(modifier.fillMaxWidth().background(background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.status_open), onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("status_strip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(status.text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
