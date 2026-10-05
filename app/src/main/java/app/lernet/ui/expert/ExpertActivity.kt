package app.lernet.ui.expert

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.policy.ExpertConnectionObservation
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.ui.components.PanelCard

internal enum class ExpertActivityFilter(val label: Int) {
    ALL(R.string.expert_activity_all),
    ACTIVE(R.string.expert_activity_active),
    CLOSED(R.string.expert_activity_closed),
    UNKNOWN(R.string.expert_activity_unknown),
}

internal fun filterExpertConnections(
    connections: List<ExpertConnectionObservation>,
    query: String,
    filter: ExpertActivityFilter,
): List<ExpertConnectionObservation> = connections.filter { connection ->
    val stateMatches = when (filter) {
        ExpertActivityFilter.ALL -> true
        ExpertActivityFilter.ACTIVE -> connection.active == true
        ExpertActivityFilter.CLOSED -> connection.active == false
        ExpertActivityFilter.UNKNOWN -> connection.active == null
    }
    val search = query.trim()
    stateMatches &&
        (
            search.isEmpty() ||
                connection.application?.contains(search, ignoreCase = true) == true ||
                connection.destination.contains(search, ignoreCase = true)
            )
}

@Composable
internal fun ExpertActivity(runtime: ExpertRuntimeState, bundle: TransferBundle) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ExpertActivityFilter.ALL) }
    val connections = filterExpertConnections(runtime.connections, query, filter)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.expert_activity_search)) },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExpertActivityFilter.entries.forEach { option ->
                FilterChip(filter == option, { filter = option }, label = { Text(stringResource(option.label)) })
            }
        }
        if (connections.isEmpty()) Text(stringResource(R.string.expert_activity_empty))
        connections.asReversed().forEach { connection ->
            PanelCard {
                Text(
                    connection.application ?: stringResource(R.string.expert_unknown_app),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(connection.destination, style = MaterialTheme.typography.bodyMedium)
                Text(connection.decision, style = MaterialTheme.typography.bodySmall)
                val applied = runtime.appliedPolicy?.takeIf { connection.policyRevision == it.revision }
                connection.exit?.let { exit ->
                    Text(
                        stringResource(
                            R.string.expert_connection_exit,
                            if (applied != null) exitTitle(exit, bundle, applied) else exitIdentity(exit),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (applied == null) ExpertHint(R.string.expert_connection_historical)
                Text(connectionStateTitle(connection.active))
                Text(
                    stringResource(R.string.expert_connection_started, observationTimeTitle(connection.startedAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(connection.protocol, style = MaterialTheme.typography.labelSmall)
                Text(
                    stringResource(
                        R.string.expert_observation_bytes, connection.uploadedBytes, connection.downloadedBytes,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    stringResource(R.string.expert_observation_rule, connection.nodeIds.size),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    stringResource(
                        R.string.expert_observation_revision,
                        connection.policyRevision?.toString() ?: stringResource(R.string.expert_no_revision),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                val names = applied?.let { (listOf(it.device) + it.trees).flatMap { tree -> tree.nodes } }
                    .orEmpty().associateBy { it.id }
                if (connection.nodeIds.isEmpty()) ExpertHint(R.string.expert_observation_no_rule)
                connection.nodeIds.forEach { id ->
                    Text(
                        names[id]?.title?.ifBlank { stringResource(R.string.expert_rule_unnamed) } ?: id,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
