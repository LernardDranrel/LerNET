package app.lernet.ui.expert

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import app.lernet.engine.policy.ExpertConnectionObservation
import app.lernet.engine.policy.ExpertExitKey
import app.lernet.engine.policy.TunIdentity
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.lernet.routing.policy.NetworkPolicy

internal val ExpertPolicySaver = Saver<NetworkPolicy, String>(
    save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) },
)

internal inline fun <reified T> ExpertOptionalJsonSaver() = Saver<T?, String>(
    save = { it?.let { value -> Json.encodeToString(value) } ?: "" },
    restore = { it.takeIf(String::isNotEmpty)?.let { raw -> Json.decodeFromString<T>(raw) } },
)

internal val ExpertScopeSaver = Saver<PolicyScope, String>(
    save = { Json.encodeToString(it) },
    restore = { Json.decodeFromString(it) },
)

internal val ExpertNodeSaver = Saver<PolicyNode, String>(
    save = { Json.encodeToString(it) },
    restore = { Json.decodeFromString(it) },
)

internal val ExpertOptionalNodeSaver = Saver<PolicyNode?, String>(
    save = { it?.let { node -> Json.encodeToString(node) } ?: "" },
    restore = { it.takeIf(String::isNotEmpty)?.let { raw -> Json.decodeFromString(raw) } },
)

internal val ExpertObservationSaver = mapSaver<ExpertConnectionObservation?>(
    save = { observation ->
        buildMap<String, Any> {
            observation?.let { item ->
                put("id", item.id)
                put("destination", item.destination)
                put("protocol", item.protocol)
                put("decision", item.decision)
                put("upload", item.uploadedBytes)
                put("download", item.downloadedBytes)
                put("nodes", ArrayList(item.nodeIds))
                put("packages", ArrayList(item.packageNames))
                put("channels", ArrayList(item.exit?.channelPath.orEmpty()))
                listOf(
                    "application" to item.application, "active" to item.active, "started" to item.startedAtMs,
                    "revision" to item.policyRevision, "sourceIp" to item.sourceIp, "sourcePort" to item.sourcePort,
                    "destinationIp" to item.destinationIp, "destinationPort" to item.destinationPort, "domain" to item.domain,
                    "process" to item.processName, "network" to item.network, "sniffed" to item.sniffedProtocol,
                    "country" to item.geoCountry, "observed" to item.observedAtMs, "updated" to item.lastUpdateAtMs,
                    "closed" to item.closedAtMs, "state" to item.state, "error" to item.errorReason,
                    "errorStage" to item.errorStage, "closeReason" to item.closeReason,
                    "instance" to item.identity?.instanceId, "interface" to item.identity?.interfaceId,
                    "exitProfile" to item.exit?.profileId, "exitChannel" to item.exit?.channelId, "exitFolder" to item.exit?.folderId,
                ).forEach { (key, value) -> if (value != null) put(key, value) }
            }
        }
    },
    restore = { saved ->
        (saved["id"] as? String)?.let { id ->
            ExpertConnectionObservation(
                id = id, application = saved["application"] as? String, destination = saved["destination"] as String,
                protocol = saved["protocol"] as String, decision = saved["decision"] as String,
                nodeIds = (saved["nodes"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                uploadedBytes = saved["upload"] as Long, downloadedBytes = saved["download"] as Long,
                active = saved["active"] as? Boolean, startedAtMs = saved["started"] as? Long,
                policyRevision = saved["revision"] as? Long, sourceIp = saved["sourceIp"] as? String,
                sourcePort = saved["sourcePort"] as? Int, destinationIp = saved["destinationIp"] as? String,
                destinationPort = saved["destinationPort"] as? Int, domain = saved["domain"] as? String,
                processName = saved["process"] as? String, network = saved["network"] as? String,
                sniffedProtocol = saved["sniffed"] as? String, geoCountry = saved["country"] as? String,
                observedAtMs = saved["observed"] as? Long, lastUpdateAtMs = saved["updated"] as? Long,
                closedAtMs = saved["closed"] as? Long, state = saved["state"] as? String,
                errorReason = saved["error"] as? String, errorStage = saved["errorStage"] as? String,
                closeReason = saved["closeReason"] as? String,
                packageNames = (saved["packages"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                identity = (saved["instance"] as? String)?.let { TunIdentity(it, saved["interface"] as? String) },
                exit = (saved["exitProfile"] as? String)?.let {
                    ExpertExitKey(
                        it, saved["exitChannel"] as? String,
                        (saved["channels"] as? List<*>)?.filterIsInstance<String>().orEmpty(), saved["exitFolder"] as? String
                    )
                },
            )
        }
    },
)
