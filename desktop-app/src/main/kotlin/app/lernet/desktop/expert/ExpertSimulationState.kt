package app.lernet.desktop.expert

import androidx.compose.runtime.saveable.mapSaver
import app.lernet.routing.policy.PolicySimulationInput

internal val ExpertInputSaver = mapSaver<PolicySimulationInput>(
    save = { value ->
        mapOf(
            "domain" to value.domain,
            "destinationIp" to value.destinationIp,
            "sourceIp" to value.sourceIp,
            "processName" to value.processName,
            "packageName" to value.packageName,
            "network" to value.network,
            "protocol" to value.protocol,
            "destinationPort" to value.destinationPort,
            "sourcePort" to value.sourcePort,
            "geoCountry" to value.geoCountry,
        )
    },
    restore = { saved ->
        PolicySimulationInput(
            domain = saved["domain"] as? String,
            destinationIp = saved["destinationIp"] as? String,
            sourceIp = saved["sourceIp"] as? String,
            processName = saved["processName"] as? String,
            packageName = saved["packageName"] as? String,
            network = saved["network"] as? String,
            protocol = saved["protocol"] as? String,
            destinationPort = saved["destinationPort"] as? Int,
            sourcePort = saved["sourcePort"] as? Int,
            geoCountry = saved["geoCountry"] as? String,
        )
    },
)

internal val ExpertConnectionSaver = mapSaver<ExpertConnection?>(
    save = { value ->
        if (value == null) {
            emptyMap()
        } else {
            mapOf(
                "id" to value.id,
                "application" to value.application,
                "destination" to value.destination,
                "protocol" to value.protocol,
                "decision" to value.decision,
                "explanation" to value.explanation,
                "routeNodeIds" to value.routeNodeIds,
                "exitId" to value.exitId,
                "uploadedBytes" to value.uploadedBytes,
                "downloadedBytes" to value.downloadedBytes,
                "active" to value.active,
                "protected" to value.protected,
                "policyRevision" to value.policyRevision,
                "startedAtMs" to value.startedAtMs,
                "domain" to value.domain,
                "destinationIp" to value.destinationIp,
                "destinationPort" to value.destinationPort,
                "sourceIp" to value.sourceIp,
                "sourcePort" to value.sourcePort,
                "processName" to value.processName,
                "processPath" to value.processPath,
                "network" to value.network,
                "sniffedProtocol" to value.sniffedProtocol,
                "geoCountry" to value.geoCountry,
                "observedAtMs" to value.observedAtMs,
                "closedAtMs" to value.closedAtMs,
                "errorReason" to value.errorReason,
                "state" to value.state,
                "lastUpdateAtMs" to value.lastUpdateAtMs,
                "errorStage" to value.errorStage,
                "closeReason" to value.closeReason,
            )
        }
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            ExpertConnection(
                id = saved["id"] as String,
                application = saved["application"] as String,
                destination = saved["destination"] as String,
                protocol = saved["protocol"] as String,
                decision = saved["decision"] as String,
                explanation = saved["explanation"] as String,
                routeNodeIds = (saved["routeNodeIds"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                exitId = saved["exitId"] as? String,
                uploadedBytes = saved["uploadedBytes"] as Long,
                downloadedBytes = saved["downloadedBytes"] as Long,
                active = saved["active"] as? Boolean,
                protected = saved["protected"] as Boolean,
                policyRevision = saved["policyRevision"] as? Long,
                startedAtMs = saved["startedAtMs"] as? Long,
                domain = saved["domain"] as? String,
                destinationIp = saved["destinationIp"] as? String,
                destinationPort = saved["destinationPort"] as? Int,
                sourceIp = saved["sourceIp"] as? String,
                sourcePort = saved["sourcePort"] as? Int,
                processName = saved["processName"] as? String,
                processPath = saved["processPath"] as? String,
                network = saved["network"] as? String,
                sniffedProtocol = saved["sniffedProtocol"] as? String,
                geoCountry = saved["geoCountry"] as? String,
                observedAtMs = saved["observedAtMs"] as? Long,
                closedAtMs = saved["closedAtMs"] as? Long,
                errorReason = saved["errorReason"] as? String,
                state = saved["state"] as? String,
                lastUpdateAtMs = saved["lastUpdateAtMs"] as? Long,
                errorStage = saved["errorStage"] as? String,
                closeReason = saved["closeReason"] as? String,
            )
        }
    },
)
