package app.lernet.routing.policy

import app.lernet.routing.RoutePlatform
import app.lernet.routing.RuleConditions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Portable routing data. Display names and canvas positions never identify a destination. */
@Serializable
data class NetworkPolicy(
    val schemaVersion: Int = VERSION,
    val revision: Long = 0,
    val device: PolicyTree = PolicyTree(PolicyScope.Device),
    val trees: List<PolicyTree> = emptyList(),
    val channels: List<PolicyChannel> = emptyList(),
    val folderPolicies: List<FolderPolicy> = emptyList(),
    val profilePolicies: List<ProfileExitPolicy> = emptyList(),
    val health: PolicyHealthSettings = PolicyHealthSettings(),
    val dns: PolicyDnsSettings = PolicyDnsSettings(),
) {
    companion object {
        const val VERSION = 1
    }
}

/** Expert DNS is independent of the ordinary VPN's legacy direct DNS setting. */
@Serializable
enum class PolicyDnsMode { SYSTEM, CUSTOM }

@Serializable
data class PolicyDnsSettings(
    val mode: PolicyDnsMode = PolicyDnsMode.SYSTEM,
    val server: String = "1.1.1.1",
) {
    fun isValid(): Boolean = server.length <= 64 &&
        (mode == PolicyDnsMode.SYSTEM || validServer(server))

    companion object {
        fun validServer(value: String): Boolean {
            val parts = value.split('.')
            return parts.size == 4 &&
                parts.all {
                    it.isNotEmpty() &&
                        it.length <= 3 &&
                        it.all { char -> char in '0'..'9' } &&
                        it.toIntOrNull()?.let { number -> number in 0..255 && number.toString() == it } == true
                } &&
                parts.first().toIntOrNull()?.let { it in 1..223 } == true
        }
    }
}

@Serializable
sealed interface PolicyScope {
    @Serializable
    @SerialName("device")
    data object Device : PolicyScope

    @Serializable
    @SerialName("profile")
    data class Profile(val id: String) : PolicyScope

    @Serializable
    @SerialName("folder")
    data class Folder(val id: String) : PolicyScope
}

@Serializable
data class PolicyTree(
    val scope: PolicyScope,
    val nodes: List<PolicyNode> = emptyList(),
    val defaultTarget: PolicyTarget = PolicyTarget.Direct,
    /** World coordinates. Reserved node IDs use PolicyCanvasKeys.node; positions never select a route. */
    val positions: Map<String, PolicyCanvasPoint> = emptyMap(),
)

@Serializable
data class PolicyCanvasPoint(val x: Float, val y: Float) {
    fun isValid(): Boolean = x.isFinite() &&
        y.isFinite() &&
        x in -MAX_COORDINATE..MAX_COORDINATE &&
        y in -MAX_COORDINATE..MAX_COORDINATE

    companion object {
        const val MAX_COORDINATE = 1_000_000f
    }
}

/** Keeps arbitrary imported node IDs distinct from the root and physical channel keys. */
object PolicyCanvasKeys {
    const val ROOT = "root"

    fun node(id: String): String = if (id == ROOT || id.startsWith("channel:") || id.startsWith("node:")) "node:$id" else id
    fun channel(id: String): String = "channel:$id"
    fun nodeId(key: String): String = if (key.startsWith("node:")) key.removePrefix("node:") else key
}

@Serializable
data class PolicyNode(
    val id: String,
    val parentId: String? = null,
    val sortIndex: Int = 0,
    val title: String = "",
    val enabled: Boolean = true,
    val conditions: RuleConditions = RuleConditions(),
    val target: PolicyTarget = PolicyTarget.Direct,
    val protected: Boolean = false,
    val redirect: DestinationRedirect? = null,
    /** Detached nodes remain editable but cannot execute. */
    val detached: Boolean = false,
    /** Last unconditional branch at this level; its children can form another complete level. */
    val otherwise: Boolean = false,
)

@Serializable
sealed interface PolicyTarget {
    @Serializable
    @SerialName("direct")
    data object Direct : PolicyTarget

    @Serializable
    @SerialName("block")
    data object Block : PolicyTarget

    /** Exit of the current profile/folder tree, not a lookup by name. */
    @Serializable
    @SerialName("current_exit")
    data object CurrentExit : PolicyTarget

    @Serializable
    @SerialName("profile")
    data class Profile(
        val id: String,
        val routeScope: PolicyScope? = null,
        val fallback: UnavailableFallback = UnavailableFallback.BLOCK,
    ) : PolicyTarget

    @Serializable
    @SerialName("folder")
    data class Folder(
        val id: String,
        val routeScope: PolicyScope? = PolicyScope.Folder(id),
        val fallback: UnavailableFallback = UnavailableFallback.BLOCK,
    ) : PolicyTarget

    @Serializable
    @SerialName("channel")
    data class Channel(val id: String) : PolicyTarget
}

@Serializable
enum class UnavailableFallback { BLOCK, DIRECT }

@Serializable
data class PolicyChannel(
    val id: String,
    val name: String,
    val owner: PolicyScope,
    val target: PolicyTarget = PolicyTarget.CurrentExit,
    val lifecycle: ExitLifecyclePolicy = ExitLifecyclePolicy(),
)

/** Destination rewriting is distinct from source address translation. */
@Serializable
data class DestinationRedirect(val address: String? = null, val port: Int? = null) {
    fun over(
        previous: DestinationRedirect?
    ): DestinationRedirect = DestinationRedirect(address ?: previous?.address, port ?: previous?.port)
}

@Serializable
enum class FolderSelection { PREFERRED, LOWEST_LATENCY }

@Serializable
data class FolderPolicy(
    val folderId: String,
    val selection: FolderSelection = FolderSelection.PREFERRED,
    val preferredProfileId: String? = null,
    val autoSwap: Boolean = false,
    val freshnessMs: Long = 45_000,
    val cooldownMs: Long = 30_000,
    val lifecycle: ExitLifecyclePolicy = ExitLifecyclePolicy(),
)

/** Standalone profile lifecycle. A folder or named channel can explicitly override it. */
@Serializable
data class ProfileExitPolicy(
    val profileId: String,
    val lifecycle: ExitLifecyclePolicy = ExitLifecyclePolicy(),
)

@Serializable
data class ExitLifecyclePolicy(
    val coldStart: Boolean = false,
    val idleTimeoutMs: Long = 15 * 60_000,
    val firstFlowTimeoutMs: Long = 30_000,
    val startupTimeoutMs: Long = 45_000,
    val maxPendingFlows: Int = 100,
)

/** Random active checks detect broken exits. Manual candidate checks retain their separate longer budget. */
@Serializable
data class PolicyHealthSettings(
    val minimumIntervalMs: Long = 3_000,
    val maximumIntervalMs: Long = 7_000,
    val activeTimeoutMs: Long = 4_000,
    val failedChecksBeforeRecovery: Int = 2,
) {
    fun isValid(): Boolean = minimumIntervalMs in 1_000..60_000 &&
        maximumIntervalMs in minimumIntervalMs..60_000 &&
        activeTimeoutMs in 1_000..15_000 &&
        failedChecksBeforeRecovery in 1..10
}

/** Inventory is supplied by repositories; the policy contains references, never credentials. */
data class PolicyInventory(
    val profileIds: Set<String>,
    val folderMembers: Map<String, List<String>>,
    val profilePlatformRequirements: Map<String, RoutePlatform> = emptyMap(),
)
