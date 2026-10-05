package app.lernet.routing.policy

import app.lernet.routing.ConditionJson
import app.lernet.routing.FieldError
import app.lernet.routing.RoutePlatform
import kotlinx.serialization.json.JsonObject

data class PolicyExit(
    val target: PolicyTarget,
    val channelId: String? = null,
    /** Logical traversal trace. Only its final stable channel identifies the physical resource. */
    val channelPath: List<String> = channelId?.let(::listOf).orEmpty(),
) {
    val physicalChannelPath: List<String> get() = canonicalChannelPath(channelPath.ifEmpty { listOfNotNull(channelId) })
}

/** Channel IDs are globally unique and already carry their owner; incoming ancestors do not create another exit. */
fun canonicalChannelPath(path: List<String>): List<String> = path.takeLast(1)

data class ProgramRule(
    val condition: JsonObject,
    val target: PolicyExit,
    val nodeIds: List<String>,
    val scopes: List<PolicyScope>,
    val protected: Boolean,
    val redirect: DestinationRedirect? = null,
)

data class PolicyProgram(
    val revision: Long,
    val rules: List<ProgramRule>,
    val errors: List<FieldError> = emptyList(),
    val inactiveNodeIds: Set<String> = emptySet(),
    val inactiveProtections: List<InactivePolicyProtection> = emptyList(),
    val unavailableExitProfileIds: Set<String> = emptySet(),
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Portable protection exists in the archive but its owner predicate belongs to another platform. */
data class InactivePolicyProtection(
    val nodeId: String,
    val scope: PolicyScope,
    val requiredPlatform: RoutePlatform,
)

/** Expands referenced trees while retaining their conditions and a single selected exit context. */
object PolicyProgramCompiler {
    private const val MAX_RULES = 20_000

    fun compile(policy: NetworkPolicy, inventory: PolicyInventory, platform: RoutePlatform): PolicyProgram {
        val errors = PolicyValidator.validate(policy, inventory)
        if (errors.isNotEmpty()) return PolicyProgram(policy.revision, emptyList(), errors)
        val trees = (listOf(policy.device) + policy.trees).associateBy { it.scope }
        val channels = policy.channels.associateBy { it.id }
        val projections = trees.mapValues { (_, tree) -> PolicyPaths.project(tree, platform) }
        val inactive = projections.values.flatMap { it.inactiveNodeIds }.toMutableSet()
        val reachedScopes = mutableSetOf<PolicyScope>(PolicyScope.Device)
        val output = mutableListOf<ProgramRule>()
        val unavailableProfiles = mutableSetOf<String>()
        var limitExceeded = false
        fun emit(rule: ProgramRule) {
            if (output.size < MAX_RULES) output += rule else limitExceeded = true
        }
        fun expand(
            target: PolicyTarget,
            condition: JsonObject,
            nodeIds: List<String>,
            scopes: List<PolicyScope>,
            protected: Boolean,
            redirect: DestinationRedirect?,
            exit: PolicyTarget?,
            channelPath: List<String>,
            depth: Int,
        ) {
            if (limitExceeded || depth > 64) {
                limitExceeded = true
                return
            }
            when (target) {
                PolicyTarget.Direct, PolicyTarget.Block -> {
                    emit(ProgramRule(condition, PolicyExit(target), nodeIds, scopes, protected, redirect))
                }
                PolicyTarget.CurrentExit -> {
                    val current = requireNotNull(exit) { "Validated tree must have an exit context" }
                    val resolved = when (current) {
                        is PolicyTarget.Profile -> current.copy(
                            routeScope = null, fallback = if (protected) UnavailableFallback.BLOCK else current.fallback,
                        )
                        is PolicyTarget.Folder -> current.copy(
                            routeScope = null, fallback = if (protected) UnavailableFallback.BLOCK else current.fallback,
                        )
                        else -> error("Invalid exit context")
                    }
                    emit(
                        ProgramRule(
                            condition, PolicyExit(resolved, channelPath.lastOrNull(), channelPath), nodeIds, scopes, protected, redirect,
                        ),
                    )
                }
                is PolicyTarget.Channel -> expand(
                    channels.getValue(target.id).target, condition, nodeIds, scopes,
                    protected, redirect, exit, channelPath + target.id, depth + 1
                )
                is PolicyTarget.Profile, is PolicyTarget.Folder -> {
                    if (target is PolicyTarget.Profile &&
                        inventory.profilePlatformRequirements[target.id]?.let { it != platform } == true
                    ) {
                        inactive += nodeIds
                        unavailableProfiles += target.id
                        target.routeScope?.let { scope -> inactive += trees.getValue(scope).nodes.map { it.id } }
                        // Unsupported transports cannot fall through to the device's direct default.
                        emit(ProgramRule(condition, PolicyExit(PolicyTarget.Block), nodeIds, scopes, protected, null))
                        return
                    }
                    val scope = when (target) {
                        is PolicyTarget.Profile -> target.routeScope
                        is PolicyTarget.Folder -> target.routeScope
                    }
                    if (scope == null) {
                        emit(
                            ProgramRule(
                                condition, PolicyExit(target, channelPath.lastOrNull(), channelPath), nodeIds, scopes, protected, redirect,
                            )
                        )
                    } else {
                        reachedScopes += scope
                        val tree = trees.getValue(scope)
                        projections.getValue(scope).paths.forEach { branch ->
                            val folded = ConditionJson.andAll(listOf(condition, branch.condition).filter { it.isNotEmpty() })
                            expand(
                                branch.target, folded, nodeIds + branch.nodeIds, scopes + scope,
                                protected || branch.protected, branch.redirect?.over(redirect) ?: redirect, target, channelPath, depth + 1
                            )
                        }
                        expand(tree.defaultTarget, condition, nodeIds, scopes + scope, protected, redirect, target, channelPath, depth + 1)
                    }
                }
            }
        }
        projections.getValue(PolicyScope.Device).paths.forEach { path ->
            expand(
                path.target, path.condition, path.nodeIds, listOf(PolicyScope.Device), path.protected, path.redirect, null, emptyList(), 0,
            )
        }
        expand(
            policy.device.defaultTarget, JsonObject(emptyMap()), emptyList(), listOf(PolicyScope.Device), false, null, null, emptyList(), 0,
        )
        val inactiveProtections = trees.values.filter { it.scope in reachedScopes }.flatMap { tree ->
            tree.nodes.filter { it.protected && it.id in projections.getValue(tree.scope).foreignOwnerInactiveNodeIds }
                .map { node ->
                    val required = if (platform == RoutePlatform.WINDOWS) RoutePlatform.ANDROID else RoutePlatform.WINDOWS
                    InactivePolicyProtection(node.id, tree.scope, required)
                }
        }
        val programErrors = if (limitExceeded) listOf(FieldError(null, "size", "Слишком много путей после раскрытия схем")) else emptyList()
        return PolicyProgram(
            policy.revision, if (programErrors.isEmpty()) output else emptyList(), programErrors, inactive, inactiveProtections,
            unavailableProfiles
        )
    }
}
