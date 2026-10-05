package app.lernet.ui.expert

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lernet.R
import app.lernet.config.policy.PolicyMigration
import app.lernet.config.transfer.TransferBundle
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.UnavailableFallback

@Composable
internal fun ExpertTargetSelector(
    target: PolicyTarget,
    scope: PolicyScope,
    bundle: TransferBundle,
    policy: NetworkPolicy,
    protected: Boolean,
    includeChannels: Boolean = true,
    onChange: (PolicyTarget) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var chooseTree by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(targetTitle(target, bundle, policy))
        }
        val routeScope = when (target) {
            is PolicyTarget.Profile -> target.routeScope
            is PolicyTarget.Folder -> target.routeScope
            PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel -> null
        }
        if (target is PolicyTarget.Profile || target is PolicyTarget.Folder) {
            Text(stringResource(R.string.expert_use_target_tree), style = MaterialTheme.typography.labelLarge)
            OutlinedButton({ chooseTree = true }, Modifier.fillMaxWidth()) {
                Text(routeScope?.let { scopeTitle(it, bundle) } ?: stringResource(R.string.expert_no_child_tree))
            }
            Text(stringResource(R.string.expert_target_tree_hint), style = MaterialTheme.typography.bodySmall)
            if (chooseTree) {
                val scopes = when (target) {
                    is PolicyTarget.Profile -> listOf(PolicyScope.Profile(target.id)) +
                        bundle.groups.filter { target.id in it.profileIds }.map { PolicyScope.Folder(it.id) }
                    is PolicyTarget.Folder -> listOf(PolicyScope.Folder(target.id))
                    else -> error("Target type is guarded")
                }
                AlertDialog(
                    onDismissRequest = { chooseTree = false }, title = { Text(stringResource(R.string.expert_use_target_tree)) },
                    text = {
                        Column {
                            (listOf<PolicyScope?>(null) + scopes).forEach { owner ->
                                TextButton(onClick = {
                                    onChange(
                                        when (target) {
                                            is PolicyTarget.Profile -> target.copy(routeScope = owner)
                                            is PolicyTarget.Folder -> target.copy(routeScope = owner)
                                        }
                                    )
                                    chooseTree = false
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(owner?.let { scopeTitle(it, bundle) } ?: stringResource(R.string.expert_no_child_tree))
                                }
                            }
                        }
                    }, confirmButton = { TextButton({ chooseTree = false }) { Text(stringResource(R.string.cancel)) } }
                )
            }
            val fallback = when (target) {
                is PolicyTarget.Profile -> target.fallback
                is PolicyTarget.Folder -> target.fallback
            }
            Text(stringResource(R.string.expert_fallback), style = MaterialTheme.typography.labelLarge)
            UnavailableFallback.entries.forEach { choice ->
                val title = if (choice == UnavailableFallback.BLOCK) R.string.expert_fallback_block else R.string.expert_fallback_direct
                ListItem(
                    headlineContent = { Text(stringResource(title)) },
                    leadingContent = {
                        RadioButton(
                            selected = fallback == choice, enabled = !protected || choice == UnavailableFallback.BLOCK,
                            onClick = {
                                onChange(
                                    when (target) {
                                        is PolicyTarget.Profile -> target.copy(fallback = choice)
                                        is PolicyTarget.Folder -> target.copy(fallback = choice)
                                    }
                                )
                            }
                        )
                    },
                )
            }
        }
    }
    if (open) {
        val choices = buildList<PolicyTarget> {
            add(PolicyTarget.Direct)
            add(PolicyTarget.Block)
            if (scope != PolicyScope.Device) add(PolicyTarget.CurrentExit)
            bundle.groups.forEach { add(PolicyTarget.Folder(it.id)) }
            bundle.profiles.forEach { add(PolicyTarget.Profile(it.id, PolicyMigration.effectiveScope(bundle, it.id))) }
            if (includeChannels) policy.channels.filter { it.owner == scope }.forEach { add(PolicyTarget.Channel(it.id)) }
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.expert_path)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        TextButton(
                            onClick = {
                                onChange(choice)
                                open = false
                            },
                            enabled = !protected || choice != PolicyTarget.Direct,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(targetTitle(choice, bundle, policy), Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
