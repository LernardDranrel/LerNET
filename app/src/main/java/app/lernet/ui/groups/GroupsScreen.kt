package app.lernet.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.lernet.R
import app.lernet.config.model.Group
import app.lernet.ui.components.PanelCard
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    state: GroupsUiState,
    onIntent: (GroupsIntent) -> Unit,
    onBack: () -> Unit,
    onOpenRoutes: (String) -> Unit,
) {
    var rename by remember { mutableStateOf<Group?>(null) }
    var pendingDelete by remember { mutableStateOf<Group?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.groups_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LerNetSymbols.arrowBack(), contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(LerNetDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Text(stringResource(R.string.groups_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.draftName,
                onValueChange = { onIntent(GroupsIntent.SetDraftName(it)) },
                label = { Text(stringResource(R.string.group_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { onIntent(GroupsIntent.Create) },
                enabled = state.draftName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().lernetButton(),
            ) {
                Text(stringResource(R.string.create_group))
            }
            if (state.groups.isEmpty()) {
                Text(stringResource(R.string.no_groups), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val listState = rememberLazyListState()
            val haptic = LocalHapticFeedback.current
            val reorderable = rememberReorderableLazyListState(listState) { from, to ->
                val id = state.groups.getOrNull(from.index)?.id ?: return@rememberReorderableLazyListState
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                onIntent(GroupsIntent.MoveGroup(id, to.index))
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
                modifier = Modifier.weight(1f),
            ) {
                items(state.groups, key = { it.id }) { group ->
                    ReorderableItem(reorderable, key = group.id) { _ ->
                        GroupCard(
                            group = group,
                            state = state,
                            scope = this,
                            onIntent = onIntent,
                            onRename = { rename = group },
                            onDelete = { pendingDelete = group },
                            onOpenRoutes = { onOpenRoutes(group.id) },
                        )
                    }
                }
            }
        }
    }
    rename?.let { group ->
        RenameGroupDialog(
            current = group.name,
            onDismiss = { rename = null },
            onConfirm = { name ->
                onIntent(GroupsIntent.Rename(group.id, name))
                rename = null
            },
        )
    }
    pendingDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.group_delete_title)) },
            text = { Text(stringResource(R.string.group_delete_body, group.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onIntent(GroupsIntent.Delete(group.id))
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    group: Group,
    state: GroupsUiState,
    scope: ReorderableCollectionItemScope,
    onIntent: (GroupsIntent) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenRoutes: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var addMenu by remember { mutableStateOf(false) }
    val candidates = state.profiles.filter { it.id !in group.profileIds }
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {},
                modifier = with(scope) {
                    Modifier.draggableHandle(
                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                        onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                    )
                },
            ) {
                Icon(LerNetSymbols.drag(), contentDescription = stringResource(R.string.group_grip))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.failover_members, group.profileIds.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (group.hasRoutes) {
                    Text(
                        stringResource(R.string.group_routes_active),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap)) {
            TextButton(onClick = onRename, modifier = Modifier.lernetButton()) {
                Text(stringResource(R.string.rename))
            }
            TextButton(onClick = onDelete, modifier = Modifier.lernetButton()) {
                Text(stringResource(R.string.delete))
            }
            FilledTonalButton(onClick = onOpenRoutes, modifier = Modifier.lernetButton()) {
                Text(stringResource(R.string.group_routes))
            }
        }
        GroupMembers(group, state.profiles, onIntent)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = { addMenu = true },
                enabled = candidates.isNotEmpty(),
                modifier = Modifier.lernetButton(),
            ) {
                Text(stringResource(R.string.group_add_member))
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                candidates.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            addMenu = false
                            onIntent(GroupsIntent.ToggleMember(group.id, profile.id))
                        },
                    )
                }
            }
        }
        if (candidates.isEmpty() && group.profileIds.isEmpty()) {
            Text(
                stringResource(R.string.group_empty_drop),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RenameGroupDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var draft by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_group)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = draft.trim().isNotEmpty()) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
