package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.policy.PolicyMigration
import app.lernet.config.transfer.TransferBundle
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.DestinationRedirect
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.UnavailableFallback
import app.lernet.ui.routes.AppSelectionField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpertRuleEditor(
    initial: PolicyNode,
    scope: PolicyScope,
    bundle: TransferBundle,
    policy: NetworkPolicy,
    onCommit: (PolicyNode) -> Unit,
    onDismiss: () -> Unit,
) {
    var edited by remember(initial.id) { mutableStateOf(initial) }
    var address by remember(initial.id) { mutableStateOf(initial.redirect?.address.orEmpty()) }
    var port by remember(initial.id) { mutableStateOf(initial.redirect?.port?.toString().orEmpty()) }
    var errors by remember(initial.id) { mutableStateOf(emptyList<String>()) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 660.dp).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.expert_rule_editor), style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    edited.title,
                    { edited = edited.copy(title = it) },
                    label = { Text(stringResource(R.string.expert_rule_title)) },
                    modifier = Modifier.fillMaxWidth()
                )
                ExpertCheckRow(R.string.expert_rule_enabled, edited.enabled) {
                    edited = edited.copy(enabled = it)
                }
                ExpertCheckRow(R.string.expert_rule_protected, edited.protected) { enabled ->
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
                    edited = edited.copy(protected = enabled, target = target)
                }
                if (edited.protected) ExpertHint(R.string.expert_protected_hint)
                if (initial.detached) ExpertHint(R.string.expert_detached)
                Text(
                    stringResource(R.string.expert_condition_join),
                    style = MaterialTheme.typography.titleSmall,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    MatchJoin.entries.forEachIndexed { index, join ->
                        SegmentedButton(
                            edited.conditions.join == join,
                            { edited = edited.copy(conditions = edited.conditions.copy(join = join)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            label = {
                                Text(
                                    stringResource(
                                        if (join == MatchJoin.AND) {
                                            R.string.expert_join_and
                                        } else {
                                            R.string.expert_join_or
                                        },
                                    )
                                )
                            }
                        )
                    }
                }
                ExpertHint(R.string.expert_condition_values_hint)
                ExpertHint(R.string.expert_domain_identity_limit)
                ConditionKind.entries.forEach { kind ->
                    val indexes = edited.conditions.blocks.indices.filter {
                        edited.conditions.blocks[it].kind == kind
                    }
                    if (kind == ConditionKind.PRIVATE &&
                        (
                            indexes.isEmpty() ||
                                indexes.size == 1 &&
                                edited.conditions.blocks[indexes.single()].values == listOf("private")
                            )
                    ) {
                        ExpertCheckRow(R.string.expert_condition_private, indexes.isNotEmpty()) { checked ->
                            edited = edited.withBlock(
                                kind,
                                if (checked) listOf("private") else emptyList(),
                                indexes.firstOrNull(),
                            )
                        }
                    } else {
                        (indexes.ifEmpty { listOf(-1) }).forEach { index ->
                            val values = edited.conditions.blocks.getOrNull(index)?.values.orEmpty()
                            val label = when (kind) {
                                ConditionKind.DOMAIN -> R.string.expert_condition_domain
                                ConditionKind.CIDR -> R.string.expert_condition_cidr
                                ConditionKind.APP -> R.string.expert_condition_app
                                ConditionKind.PROCESS -> R.string.expert_condition_process
                                ConditionKind.GEOIP -> R.string.expert_condition_geoip
                                ConditionKind.PRIVATE -> R.string.expert_condition_private
                            }
                            OutlinedTextField(
                                values.joinToString("\n"),
                                { raw -> edited = edited.withBlock(kind, raw.lines(), index.takeIf { it >= 0 }) },
                                label = { Text(stringResource(label)) },
                                modifier = Modifier.fillMaxWidth(), minLines = 2,
                                supportingText = if (kind == ConditionKind.PROCESS) {
                                    { Text(stringResource(R.string.expert_inactive_android)) }
                                } else {
                                    null
                                },
                            )
                            if (kind == ConditionKind.APP) {
                                AppSelectionField(values) {
                                    edited = edited.withBlock(kind, it, index.takeIf { it >= 0 })
                                }
                            }
                        }
                    }
                    if (kind != ConditionKind.PRIVATE && indexes.isNotEmpty()) {
                        TextButton({
                            edited = edited.copy(
                                conditions = edited.conditions.copy(
                                    blocks = edited.conditions.blocks + ConditionBlock(kind),
                                ),
                            )
                        }) { Text(stringResource(R.string.expert_condition_add_block)) }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.expert_path), style = MaterialTheme.typography.titleMedium)
                ExpertTargetSelector(edited.target, scope, bundle, policy, edited.protected) {
                    edited = edited.copy(target = it)
                }
                HorizontalDivider()
                Text(stringResource(R.string.expert_redirect), style = MaterialTheme.typography.titleMedium)
                ExpertHint(R.string.expert_redirect_hint)
                OutlinedTextField(
                    address,
                    { address = it },
                    label = { Text(stringResource(R.string.expert_redirect_address)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    port, { port = it }, label = { Text(stringResource(R.string.expert_redirect_port)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                errors.forEach {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val invalidPort = stringResource(R.string.expert_invalid_port)
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
                        val candidate = ExpertEdits.putNode(policy, scope, clean)
                        errors = PolicyProgramCompiler.compile(
                            candidate, PolicyMigration.inventory(bundle), RoutePlatform.ANDROID,
                        ).errors.map { it.message }
                        if (errors.isEmpty()) onCommit(clean)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF275C47), contentColor = Color.White,
                ),
            ) { Text(stringResource(R.string.expert_node_accept)) }
        }
    }
}

private fun PolicyNode.withBlock(kind: ConditionKind, values: List<String>, index: Int?): PolicyNode {
    val blocks = conditions.blocks.toMutableList()
    if (index == null) {
        if (kind != ConditionKind.PRIVATE || values.isNotEmpty()) blocks += ConditionBlock(kind, values)
    } else if (kind == ConditionKind.PRIVATE && values.isEmpty()) {
        blocks.removeAt(index)
    } else {
        blocks[index] = blocks[index].copy(values = values)
    }
    return copy(conditions = conditions.copy(blocks = blocks))
}

@Composable
internal fun ExpertCheckRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Checkbox(checked, onChange) },
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
