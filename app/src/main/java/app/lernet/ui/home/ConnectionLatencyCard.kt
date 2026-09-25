package app.lernet.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.engine.ConnectionState
import app.lernet.ui.components.PanelCard
import app.lernet.ui.theme.LerNetOk
import app.lernet.ui.theme.lernetButton

@Composable
internal fun ConnectionLatencyCard(state: HomeUiState, onIntent: (HomeIntent) -> Unit) {
    val profileId = state.activeProfile?.id
    val probe = profileId?.let(state.probes::get)
    val snapshot = state.snapshot
    val connected = snapshot.state == ConnectionState.CONNECTED
    val liveServerMs = snapshot.serverTcpMs.takeIf { profileId != null && snapshot.activeProfileId == profileId }
    val serverMs = if (connected) liveServerMs else probe?.tcpMs ?: liveServerMs
    val serverValue = when {
        probe?.running == true -> stringResource(R.string.home_latency_checking)
        serverMs != null -> stringResource(R.string.home_latency_ms, serverMs)
        probe != null && !probe.reachable -> stringResource(R.string.home_latency_no_response)
        else -> stringResource(R.string.home_latency_unmeasured)
    }
    PanelCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_latency_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { profileId?.let { onIntent(HomeIntent.ProbeProfiles(listOf(it))) } },
                enabled = profileId != null && probe?.running != true,
                modifier = Modifier.lernetButton(),
            ) { Text(stringResource(R.string.home_latency_check)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LatencyTile(
                title = stringResource(R.string.home_latency_server),
                value = serverValue,
                subtitle = stringResource(R.string.home_latency_server_hint),
                healthy = serverMs != null && probe?.running != true,
                modifier = Modifier.weight(1f),
            )
            LatencyTile(
                title = stringResource(R.string.home_latency_tunnel),
                value = stringResource(R.string.home_latency_unmeasured),
                subtitle = stringResource(
                    if (connected) R.string.home_latency_tunnel_hint else R.string.home_latency_tunnel_disconnected,
                ),
                healthy = false,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.home_latency_distinction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LatencyTile(title: String, value: String, subtitle: String, healthy: Boolean, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = if (healthy) LerNetOk else MaterialTheme.colorScheme.onSurface,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
