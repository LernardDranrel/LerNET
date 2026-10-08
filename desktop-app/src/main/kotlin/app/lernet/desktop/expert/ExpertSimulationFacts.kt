package app.lernet.desktop.expert

import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicySimulationInput

/** Labels may contain formatted or inferred text; only observed facts enter the matcher. */
internal fun ExpertConnection.simulationInput(): PolicySimulationInput = PolicySimulationInput(
    domain = domain,
    destinationIp = destinationIp,
    sourceIp = sourceIp,
    processName = processName,
    network = network,
    protocol = sniffedProtocol,
    destinationPort = destinationPort,
    sourcePort = sourcePort,
    geoCountry = geoCountry,
)

/** Draft revisions can equal a saved revision while their contents differ. */
internal fun ExpertUiState.recordedPolicy(revision: Long?): NetworkPolicy? = revision?.let { knownRevision ->
    applied?.takeIf { it.revision == knownRevision } ?: saved.takeIf { it.revision == knownRevision }
}
