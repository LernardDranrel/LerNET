package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.policy.PolicyMigration
import app.lernet.config.transfer.TransferBundle
import app.lernet.engine.policy.ExpertConnectionObservation
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyFlowMatcher
import app.lernet.routing.policy.PolicyPreviewConfidence
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyRoutePreview
import app.lernet.routing.policy.PolicySimulationInput
import app.lernet.ui.components.PanelCard
import app.lernet.ui.routes.AppSelectionField
import app.lernet.ui.routes.CountryPicker
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyMatchResult
import app.lernet.routing.policy.PolicyPreviewCandidate
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SimulationSource { DRAFT, CURRENT }

private enum class SimulationInputSource(val label: Int) {
    HISTORY(R.string.expert_simulation_history),
    MODIFIED(R.string.expert_simulation_modified),
    NEW(R.string.expert_simulation_new),
}

@Composable
internal fun ExpertSimulation(
    runtime: ExpertRuntimeState,
    bundle: TransferBundle,
    initialConnection: ExpertConnectionObservation? = null,
    historicalPath: Boolean = false,
    requestId: Long = 0,
    memory: ExpertFormMemory = remember { ExpertFormMemory() },
) {
    var source by rememberSaveable { mutableStateOf(SimulationSource.CURRENT) }
    var domain by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var packageName by rememberSaveable { mutableStateOf("") }
    var processName by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var network by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by memory.preview
    var busy by remember { mutableStateOf(false) }
    var inputSource by rememberSaveable {
        mutableStateOf(SimulationInputSource.HISTORY)
    }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var history by rememberSaveable(stateSaver = ExpertObservationSaver) { mutableStateOf(initialConnection) }
    var protocol by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceIp by rememberSaveable { mutableStateOf("") }
    var sourcePort by rememberSaveable { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var loadedInitial by rememberSaveable { mutableStateOf<String?>(null) }
    var showHistoryPath by rememberSaveable { mutableStateOf(historicalPath) }
    val scope = rememberCoroutineScope()
    fun load(connection: ExpertConnectionObservation) {
        history = connection
        editorOpen = false
        showHistoryPath = false
        failure = null
        inputSource = SimulationInputSource.HISTORY
        domain = connection.domain.orEmpty()
        address = connection.destinationIp.orEmpty()
        packageName = connection.packageNames.singleOrNull().orEmpty()
        processName = connection.processName.orEmpty()
        country = connection.geoCountry.orEmpty()
        port = connection.destinationPort?.toString().orEmpty()
        network = connection.network
        protocol = connection.sniffedProtocol
        sourceIp = connection.sourceIp.orEmpty()
        sourcePort = connection.sourcePort?.toString().orEmpty()
        preview = null
    }
    LaunchedEffect(initialConnection?.id, initialConnection?.identity, historicalPath, requestId) {
        initialConnection?.let {
            val key = "${it.identity}:${it.id}:$historicalPath:$requestId"
            if (loadedInitial != key) {
                load(it)
                loadedInitial = key
                showHistoryPath = historicalPath
            }
        }
    }
    val policy = when (source) {
        SimulationSource.DRAFT -> runtime.draft
        SimulationSource.CURRENT -> runtime.appliedPolicy ?: runtime.saved
    }
    val latestPolicy by rememberUpdatedState(policy)
    val inputKey = listOf(policy, bundle, domain, address, packageName, processName, country, port, network, sourceIp, sourcePort, protocol)
    LaunchedEffect(inputKey) {
        if (memory.simulationInputs != inputKey) preview = null
        memory.simulationInputs = inputKey
    }
    val validPort = port.isBlank() || port.toIntOrNull()?.let { it in 1..65535 } == true
    val validSourcePort = sourcePort.isBlank() || sourcePort.toIntOrNull()?.let { it in 1..65535 } == true
    val validAddresses = numericIpOrEmpty(address) && numericIpOrEmpty(sourceIp)
    val inputLocked = busy || inputSource == SimulationInputSource.HISTORY
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExpertHint(R.string.expert_simulation_hint)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({
                if (inputSource == SimulationInputSource.HISTORY) history?.let { load(it) }
                inputSource = if (history == null) SimulationInputSource.NEW else SimulationInputSource.MODIFIED
                editorOpen = !editorOpen; showHistoryPath = false; preview = null
            }, enabled = !busy && (history != null || inputSource == SimulationInputSource.NEW)) {
                Text(stringResource(if (editorOpen) R.string.expert_simulation_hide_editor else R.string.expert_simulation_edit_parameters))
            }
            TextButton({
                inputSource = SimulationInputSource.NEW; history = null; editorOpen = true; showHistoryPath = false
                domain = ""; address = ""; packageName = ""; processName = ""; country = ""; port = ""
                sourceIp = ""; sourcePort = ""; network = null; protocol = null; preview = null; failure = null
            }, enabled = !busy) { Text(stringResource(R.string.expert_simulation_create_example)) }
        }
        if (inputSource != SimulationInputSource.NEW) {
            OutlinedButton({ historyOpen = true }, Modifier.fillMaxWidth(), enabled = !busy) {
                Text(history?.let { "${it.application ?: stringResource(R.string.expert_unknown_app)} → ${it.destination}" } ?: stringResource(R.string.expert_simulation_choose_history))
            }
            history?.let { observation ->
                PanelCard {
                    Text(stringResource(R.string.expert_simulation_recorded_path),
                        style = MaterialTheme.typography.titleSmall)
                    val historic = listOfNotNull(runtime.appliedPolicy, runtime.saved)
                        .firstOrNull { it.revision == observation.policyRevision }
                    Text("${observation.application ?: stringResource(R.string.expert_unknown_app)} → ${observation.destination}")
                    Text(observation.network ?: stringResource(R.string.expert_value_unknown), style = MaterialTheme.typography.bodySmall)
                    if (historic == null) ExpertHint(R.string.expert_simulation_historical_limit)
                    TextButton({ showHistoryPath = !showHistoryPath; if (showHistoryPath) editorOpen = false; preview = null }, enabled = historic != null && !busy) {
                        Text(stringResource(if (showHistoryPath) R.string.expert_flow_hide_path else R.string.expert_simulation_recorded_path))
                    }
                    if (showHistoryPath && historic != null) {
                        val trees = (listOf(historic.device) + historic.trees)
                            .filter { it.scope == PolicyScope.Device || it.nodes.any { node -> node.id in observation.nodeIds } }
                        trees.forEach { tree ->
                            Text(scopeTitle(tree.scope, bundle), style = MaterialTheme.typography.titleSmall)
                            ExpertGraph(tree, bundle, historic, emptySet(), {}, {}, {}, { _, _ -> }, {},
                                Modifier.fillMaxWidth().height(340.dp), highlightedNodes = observation.nodeIds.toSet(),
                                highlightedChannels = setOfNotNull(observation.exit?.channelId), highlightRoot = true, readOnly = true)
                        }
                    }
                }
            }
        }
        if (history == null && inputSource == SimulationInputSource.HISTORY) ExpertHint(R.string.expert_simulation_history_first)
        if (history != null || inputSource != SimulationInputSource.HISTORY) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(source != SimulationSource.DRAFT, {
                source = SimulationSource.CURRENT
                showHistoryPath = false
            }, enabled = !busy, label = { Text(stringResource(R.string.expert_simulation_current_path)) })
            if (runtime.hasDraftChanges) FilterChip(source == SimulationSource.DRAFT, {
                source = SimulationSource.DRAFT; showHistoryPath = false
            }, enabled = !busy, label = { Text(stringResource(R.string.expert_simulation_with_edits)) })
        }
        if (editorOpen) {
            Text(stringResource(inputSource.label), style = MaterialTheme.typography.titleSmall)
            SimulationField(domain, { domain = it }, R.string.expert_simulation_domain, inputLocked)
            SimulationField(address, { address = it }, R.string.expert_simulation_ip, inputLocked)
            SimulationField(packageName, { packageName = it }, R.string.expert_simulation_package, inputLocked)
            SimulationField(processName, { processName = it }, R.string.expert_simulation_process, inputLocked)
            if (!inputLocked) {
                AppSelectionField(listOfNotNull(packageName.takeIf { it.isNotBlank() })) { packageName = it.lastOrNull().orEmpty() }
                CountryPicker(listOfNotNull(country.takeIf { it.isNotBlank() })) { country = it.lastOrNull()?.removePrefix("!").orEmpty() }
            } else {
                val countryTitle = country.ifBlank { stringResource(R.string.expert_value_unknown) }
                Text(stringResource(R.string.expert_simulation_country) + ": " + countryTitle)
            }
            SimulationField(sourceIp, { sourceIp = it }, R.string.expert_simulation_source_ip, inputLocked)
            SimulationField(sourcePort, { sourcePort = it }, R.string.expert_simulation_source_port, inputLocked)
            if (!validSourcePort) ExpertHint(R.string.expert_invalid_port)
            if (!validAddresses) Text(stringResource(R.string.expert_simulation_invalid_ip), color = MaterialTheme.colorScheme.error)
            SimulationChoice(protocol, { protocol = it }, R.string.expert_simulation_protocol,
                listOf(null, "tls", "http", "quic", "dns", "ssh", "bittorrent"), inputLocked)
            ExpertHint(R.string.expert_simulation_country_hint)
            OutlinedTextField(
                port, { port = it }, label = { Text(stringResource(R.string.expert_simulation_port)) },
                singleLine = true, isError = !validPort, enabled = !inputLocked, modifier = Modifier.fillMaxWidth()
            )
            SimulationChoice(network, { network = it }, R.string.expert_flow_transport_label, listOf(null, "tcp", "udp", "icmp"), inputLocked)
        }
        if (!editorOpen && (!validPort || !validSourcePort || !validAddresses)) Text(
            stringResource(R.string.expert_simulation_invalid_example), color = MaterialTheme.colorScheme.error,
        )
        if (history != null || inputSource != SimulationInputSource.HISTORY) Button(onClick = {
            showHistoryPath = false
            val selectedPolicy = policy ?: return@Button
            val input = PolicySimulationInput(
                domain = domain.trim().ifBlank { null }, destinationIp = address.trim().ifBlank { null },
                processName = processName.trim().ifBlank { null }, packageName = packageName.trim().ifBlank { null },
                                network = network, protocol = protocol, destinationPort = port.toIntOrNull(), geoCountry = country.trim().ifBlank { null },
                sourceIp = sourceIp.trim().ifBlank { null }, sourcePort = sourcePort.toIntOrNull()
            )
            busy = true
            failure = null
            scope.launch {
                try {
                    val calculated = withContext(Dispatchers.Default) {
                        PolicyFlowMatcher.preview(
                            PolicyProgramCompiler.compile(selectedPolicy, PolicyMigration.inventory(bundle), RoutePlatform.ANDROID),
                            input,
                        )
                    }
                    if (latestPolicy == selectedPolicy) preview = selectedPolicy to calculated
                    else failure = "Схема изменилась во время расчёта. Проверьте путь ещё раз."
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure = error.message ?: error.javaClass.simpleName
                } finally {
                    busy = false
                }
            }
        }, enabled = policy != null && validPort && validSourcePort && validAddresses && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (busy) R.string.expert_simulation_busy else R.string.expert_simulation_run))
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        preview?.let { (checkedPolicy, result) ->
            ExpertPreview(result, checkedPolicy, bundle)
            val input = PolicySimulationInput(domain.trim().ifBlank { null }, address.trim().ifBlank { null },
                sourceIp.trim().ifBlank { null }, processName.trim().ifBlank { null }, packageName.trim().ifBlank { null },
                network, protocol, port.toIntOrNull(), sourcePort.toIntOrNull(), country.trim().ifBlank { null })
            ExpertSimulationPath(result, checkedPolicy, bundle, input)
        }
        if (historyOpen) {
            AlertDialog(onDismissRequest = { historyOpen = false },
                title = { Text(stringResource(R.string.expert_simulation_choose_history)) }, text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        if (runtime.connections.isEmpty()) Text(stringResource(R.string.expert_activity_empty))
                        runtime.connections.asReversed().forEach { connection ->
                            TextButton({ load(connection); historyOpen = false }, Modifier.fillMaxWidth()) {
                                Text("${connection.application ?: stringResource(R.string.expert_unknown_app)} · ${connection.destination}")
                            }
                        }
                    }
                }, confirmButton = { TextButton({ historyOpen = false }) { Text(stringResource(R.string.expert_close)) } })
        }
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
        Text(stringResource(R.string.expert_simulation_current_path), style = MaterialTheme.typography.labelSmall)
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

@Composable
private fun SimulationChoice(
    value: String?, onChange: (String?) -> Unit, label: Int, choices: List<String?>, disabled: Boolean,
) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (choices + value).distinct().forEach { option ->
            FilterChip(value == option, { onChange(option) }, enabled = !disabled,
                label = { Text(option?.uppercase() ?: stringResource(R.string.expert_simulation_unknown)) })
        }
    }
}

internal fun numericIpOrEmpty(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return true
    if (':' !in value) {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
    if (!value.all { it in "0123456789abcdefABCDEF:." }) return false
    // A colon plus only numeric address characters cannot resolve a hostname.
    return runCatching { java.net.InetAddress.getByName(value) }.isSuccess
}

@Composable
private fun ExpertSimulationPath(
    result: PolicyRoutePreview, policy: NetworkPolicy, bundle: TransferBundle, input: PolicySimulationInput,
) {
    var candidateIndex by remember(result) { mutableStateOf(0) }
    val candidate = result.selected ?: result.candidates.getOrNull(candidateIndex)
    if (candidate == null) return
    PanelCard {
        Text(stringResource(if (result.selected == null) R.string.expert_simulation_possible_path
            else R.string.expert_simulation_confirmed_path), style = MaterialTheme.typography.titleMedium)
        if (result.selected == null) {
            result.candidates.forEachIndexed { index, option ->
                FilterChip(candidateIndex == index, { candidateIndex = index },
                    label = { Text(stringResource(R.string.expert_simulation_candidate, index + 1,
                        targetTitle(option.rule.target.target, bundle, policy))) })
            }
        }
        val trees = (listOf(policy.device) + policy.trees).filter { it.scope in candidate.rule.scopes || it.scope == PolicyScope.Device }
        trees.forEach { tree ->
            Text(scopeTitle(tree.scope, bundle), style = MaterialTheme.typography.titleSmall)
            ExpertGraph(tree, bundle, policy, emptySet(), {}, {}, {}, { _, _ -> }, {},
                Modifier.fillMaxWidth().height(340.dp), highlightedNodes = candidate.rule.nodeIds.toSet(),
                highlightedChannels = candidate.rule.target.channelPath.toSet(), highlightRoot = true, readOnly = true)
        }
        Text(targetTitle(candidate.rule.target.target, bundle, policy), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.expert_simulation_steps), style = MaterialTheme.typography.titleSmall)
        val program = remember(policy, bundle) {
            PolicyProgramCompiler.compile(policy, PolicyMigration.inventory(bundle), RoutePlatform.ANDROID)
        }
        program.rules.take(candidate.ruleIndex + 1).take(32).forEach { rule ->
            val match = PolicyFlowMatcher.match(rule.condition, input)
            val names = (listOf(policy.device) + policy.trees).flatMap { it.nodes }.associateBy { it.id }
            Text(rule.nodeIds.joinToString(" → ") { id -> names[id]?.title?.takeIf { it.isNotBlank() } ?: id }
                .ifBlank { stringResource(R.string.expert_simulation_default) }, style = MaterialTheme.typography.bodySmall)
            Text(when (match.result) {
                PolicyMatchResult.MATCH -> stringResource(R.string.expert_simulation_step_match)
                PolicyMatchResult.NO_MATCH -> stringResource(R.string.expert_simulation_step_no_match)
                PolicyMatchResult.UNKNOWN -> stringResource(R.string.expert_simulation_step_unknown, match.missingFacts.joinToString(", "))
            }, style = MaterialTheme.typography.bodySmall)
        }
        if (candidate.ruleIndex >= 32) ExpertHint(R.string.expert_simulation_steps_limited)
    }
}
