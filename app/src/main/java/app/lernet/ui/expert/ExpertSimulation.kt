package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.policy.PolicyMigration
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyFlowMatcher
import app.lernet.routing.policy.PolicyPreviewConfidence
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyRoutePreview
import app.lernet.routing.policy.PolicySimulationInput
import app.lernet.ui.components.PanelCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SimulationSource(val label: Int) {
    DRAFT(R.string.expert_simulation_draft),
    SAVED(R.string.expert_simulation_saved),
    APPLIED(R.string.expert_simulation_applied),
}

@Composable
internal fun ExpertSimulation(runtime: ExpertRuntimeState, bundle: TransferBundle) {
    var source by remember { mutableStateOf(SimulationSource.DRAFT) }
    var domain by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var processName by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var network by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<NetworkPolicy, PolicyRoutePreview>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val policy = when (source) {
        SimulationSource.DRAFT -> runtime.draft
        SimulationSource.SAVED -> runtime.saved
        SimulationSource.APPLIED -> runtime.appliedPolicy
    }
    LaunchedEffect(policy, domain, address, packageName, processName, country, port, network) { preview = null }
    val validPort = port.isBlank() || port.toIntOrNull()?.let { it in 1..65535 } == true
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpertHint(R.string.expert_simulation_hint)
        Text(stringResource(R.string.expert_simulation_source), style = MaterialTheme.typography.labelLarge)
        SimulationSource.entries.forEach { choice ->
            FilterChip(
                source == choice, onClick = { source = choice },
                enabled = !busy &&
                    (choice != SimulationSource.APPLIED || runtime.appliedPolicy != null),
                label = { Text(stringResource(choice.label)) }
            )
        }
        SimulationField(domain, { domain = it }, R.string.expert_simulation_domain, busy)
        SimulationField(address, { address = it }, R.string.expert_simulation_ip, busy)
        SimulationField(packageName, { packageName = it }, R.string.expert_simulation_package, busy)
        SimulationField(processName, { processName = it }, R.string.expert_simulation_process, busy)
        SimulationField(country, { country = it }, R.string.expert_simulation_country, busy)
        ExpertHint(R.string.expert_simulation_country_hint)
        OutlinedTextField(
            port, { port = it }, label = { Text(stringResource(R.string.expert_simulation_port)) },
            singleLine = true, isError = !validPort, enabled = !busy, modifier = Modifier.fillMaxWidth()
        )
        listOf(null, "tcp", "udp", "icmp").forEach { value ->
            FilterChip(
                network == value, onClick = { network = value }, enabled = !busy,
                label = { Text(value?.uppercase() ?: stringResource(R.string.expert_simulation_unknown)) }
            )
        }
        Button(onClick = {
            val selectedPolicy = policy ?: return@Button
            val input = PolicySimulationInput(
                domain = domain.trim().ifBlank { null }, destinationIp = address.trim().ifBlank { null },
                processName = processName.trim().ifBlank { null }, packageName = packageName.trim().ifBlank { null },
                network = network, destinationPort = port.toIntOrNull(), geoCountry = country.trim().ifBlank { null }
            )
            busy = true
            scope.launch {
                try {
                    preview = selectedPolicy to withContext(Dispatchers.Default) {
                        PolicyFlowMatcher.preview(
                            PolicyProgramCompiler.compile(selectedPolicy, PolicyMigration.inventory(bundle), RoutePlatform.ANDROID),
                            input,
                        )
                    }
                } finally {
                    busy = false
                }
            }
        }, enabled = policy != null && validPort && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (busy) R.string.expert_simulation_busy else R.string.expert_simulation_run))
        }
        preview?.let { (checkedPolicy, result) -> ExpertPreview(result, checkedPolicy, bundle) }
    }
}

@Composable
private fun SimulationField(value: String, onChange: (String) -> Unit, label: Int, busy: Boolean) {
    OutlinedTextField(
        value, onChange, label = { Text(stringResource(label)) }, singleLine = true,
        enabled = !busy, modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ExpertPreview(preview: PolicyRoutePreview, policy: NetworkPolicy, bundle: TransferBundle) {
    PanelCard {
        val title = when (preview.confidence) {
            PolicyPreviewConfidence.MATCHED -> R.string.expert_simulation_matched
            PolicyPreviewConfidence.INCOMPLETE -> R.string.expert_simulation_incomplete
            PolicyPreviewConfidence.NO_MATCH -> R.string.expert_simulation_no_match
            PolicyPreviewConfidence.INVALID -> R.string.expert_simulation_invalid
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.expert_simulation_revision, policy.revision), style = MaterialTheme.typography.labelSmall)
        preview.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        if (preview.missingFacts.isNotEmpty()) {
            Text(stringResource(R.string.expert_simulation_missing, preview.missingFacts.joinToString(", ")))
        }
        val names = (listOf(policy.device) + policy.trees).flatMap { it.nodes }.associateBy { it.id }
        preview.selected?.let { selected ->
            selected.rule.nodeIds.forEach { Text(names[it]?.title?.ifBlank { stringResource(R.string.expert_rule_unnamed) } ?: it) }
            if (selected.rule.nodeIds.isEmpty()) Text(stringResource(R.string.expert_simulation_default))
            Text(targetTitle(selected.rule.target.target, bundle, policy), style = MaterialTheme.typography.titleSmall)
            if (selected.rule.protected) ExpertHint(R.string.expert_rule_protected)
            selected.rule.redirect?.let {
                Text("${it.address.orEmpty()}:${it.port?.toString().orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (preview.selected == null) {
            preview.candidates.take(12).forEachIndexed { index, candidate ->
                Text(
                    stringResource(
                        R.string.expert_simulation_candidate, index + 1,
                        candidate.rule.nodeIds.mapNotNull { names[it]?.title }.filter { it.isNotBlank() }.joinToString(" → ")
                            .ifBlank { stringResource(R.string.expert_simulation_default) }
                    )
                )
            }
        }
    }
}
