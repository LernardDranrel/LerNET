package app.lernet.ui.expert

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.policy.PolicyMigration
import app.lernet.config.transfer.TransferBundle
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.DestinationRedirect
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyBranchEditing
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.UnavailableFallback
import app.lernet.ui.routes.ConditionsComposer
import app.lernet.ui.routes.RuleEditorDialog
import app.lernet.ui.routes.branchRuleCondition
import app.lernet.ui.routes.branchRuleTitle
import kotlinx.coroutines.launch

@Composable
internal fun ExpertRuleEditor(
    initial: PolicyNode,
    scope: PolicyScope,
    bundle: TransferBundle,
    policy: NetworkPolicy,
    onCommit: suspend (PolicyNode) -> String?,
    onDismiss: () -> Unit,
) {
    var edited by rememberSaveable(initial.id, stateSaver = ExpertNodeSaver) { mutableStateOf(initial) }
    var address by rememberSaveable(initial.id) { mutableStateOf(initial.redirect?.address.orEmpty()) }
    var port by rememberSaveable(initial.id) { mutableStateOf(initial.redirect?.port?.toString().orEmpty()) }
    var errors by remember(initial.id) { mutableStateOf(emptyList<String>()) }
    var busy by remember(initial.id) { mutableStateOf(false) }
    LaunchedEffect(edited, address, port) { errors = emptyList() }
    val commitScope = androidx.compose.runtime.rememberCoroutineScope()
    val otherwise = PolicyOtherwise.isOtherwise(initial)
    val tree = remember(policy, scope) { ExpertEdits.tree(policy, scope) }
    val parent = tree.nodes.firstOrNull { it.id == edited.parentId }
    val inheritedProtection = PolicyBranchEditing.inheritedProtection(tree, edited.parentId)
    val effectiveProtection = edited.protected || inheritedProtection
    val hasChildren = tree.nodes.any { it.parentId == initial.id && !it.detached }
    var redirectExpanded by rememberSaveable(initial.id) { mutableStateOf(initial.redirect != null) }
    val invalidPort = stringResource(R.string.expert_invalid_port)
    val missingCondition = stringResource(R.string.expert_otherwise_condition_required)
    RuleEditorDialog(stringResource(if (otherwise) R.string.expert_otherwise_title else R.string.expert_editor_heading),
        onDismiss = { if (!busy) onDismiss() }, errors = {
            if (errors.isNotEmpty()) {
                Column(Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState())) {
                    errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }, actions = {
            TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = {
                    val parsedPort = port.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                    if (port.isNotBlank() && (parsedPort == null || parsedPort !in 1..65535)) {
                        errors = listOf(invalidPort)
                    } else {
                        val clean = edited.copy(
                            conditions = edited.conditions.copy(
                                blocks = edited.conditions.blocks.map { block ->
                                    block.copy(
                                        values = block.values.map(String::trim)
                                            .filter(String::isNotEmpty).distinct(),
                                    )
                                }.filter { it.values.isNotEmpty() },
                            ),
                            redirect = if (address.isBlank() && port.isBlank()) {
                                null
                            } else {
                                DestinationRedirect(
                                    address.trim().takeIf(String::isNotEmpty), parsedPort,
                                )
                            },
                        )
                        if (!otherwise &&
                            clean.conditions.blocks.isEmpty() &&
                            ExpertEdits.tree(policy, scope).nodes.none { it.id == initial.id }
                        ) {
                            errors = listOf(missingCondition)
                        } else {
                            val candidate = ExpertEdits.putNode(policy, scope, clean)
                            errors = PolicyProgramCompiler.compile(
                                candidate, PolicyMigration.inventory(bundle), RoutePlatform.ANDROID,
                            ).errors.map { it.message }
                            if (errors.isEmpty()) {
                                busy = true
                                commitScope.launch {
                                    try {
                                        val failure = onCommit(clean)
                                        if (failure == null) onDismiss() else errors = listOf(failure)
                                    } finally { busy = false }
                                }
                            }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f).testTag("expert_rule_save_draft"),
            ) { Text(stringResource(R.string.expert_editor_save_draft)) }
        },
    ) {
        if (parent != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(stringResource(R.string.expert_editor_parent, branchRuleTitle(parent)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(branchRuleCondition(parent), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (otherwise) {
            ExpertHint(R.string.expert_otherwise_hint)
            if (initial.parentId != null && ExpertEdits.tree(policy, scope).nodes.none { it.id == initial.id }) {
                ExpertHint(R.string.expert_otherwise_new_path_hint)
            }
        } else {
            OutlinedTextField(
                edited.title,
                { if (!busy) edited = edited.copy(title = it) },
                label = { Text(stringResource(R.string.expert_rule_title)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            ExpertCheckRow(R.string.expert_rule_enabled, edited.enabled) {
                if (!busy) edited = edited.copy(enabled = it)
            }
        }
        ExpertCheckRow(R.string.expert_rule_protected, effectiveProtection, enabled = !inheritedProtection && !busy) { enabled ->
            val target = when (val target = edited.target) {
                is PolicyTarget.Profile -> if (enabled) {
                    target.copy(fallback = UnavailableFallback.BLOCK)
                } else {
                    target
                }
                is PolicyTarget.Folder -> if (enabled) {
                    target.copy(fallback = UnavailableFallback.BLOCK)
                } else {
                    target
                }
                PolicyTarget.Direct -> if (enabled) PolicyTarget.Block else target
                PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel -> target
            }
            if (!busy) edited = edited.copy(protected = enabled, target = target)
        }
        if (inheritedProtection) ExpertHint(R.string.expert_protection_inherited)
        else if (edited.protected) ExpertHint(R.string.expert_protected_hint)
        if (initial.detached) ExpertHint(R.string.expert_detached)
        if (!otherwise) {
            Text(stringResource(R.string.expert_editor_conditions), style = MaterialTheme.typography.titleMedium)
            ConditionsComposer(edited.conditions, {
                if (!busy) edited = edited.copy(conditions = it)
            }, includeProcess = true, requireBlocks = false)
        }
        HorizontalDivider()
        Text(stringResource(R.string.expert_path), style = MaterialTheme.typography.titleMedium)
        if (hasChildren) {
            ExpertHint(R.string.expert_otherwise_child_exit_hint)
        } else {
            ExpertTargetSelector(edited.target, scope, bundle, policy, effectiveProtection) {
                if (!busy) edited = edited.copy(target = it)
            }
        }
        if (!otherwise) {
            HorizontalDivider()
            TextButton({ redirectExpanded = !redirectExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (redirectExpanded) R.string.expert_editor_hide_redirect else R.string.expert_redirect))
            }
        }
        if (!otherwise && redirectExpanded) {
            ExpertHint(R.string.expert_redirect_hint)
            OutlinedTextField(
                address,
                { if (!busy) address = it },
                label = { Text(stringResource(R.string.expert_redirect_address)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                port, { if (!busy) port = it }, label = { Text(stringResource(R.string.expert_redirect_port)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun ExpertCheckRow(label: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Checkbox(checked, onChange, enabled = enabled) },
    )
}

@Composable
internal fun ExpertHint(resource: Int) {
    Text(
        stringResource(resource),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
