package app.lernet.ui.routes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.lernet.R
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.layout.rememberThumbZoneBottomPadding
import app.lernet.ui.theme.LerNetDimens
import app.lernet.ui.theme.lernetButton
import app.lernet.ui.theme.lernetPrimaryAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteEditorScreen(
    state: RouteEditorUiState,
    onIntent: (RouteEditorIntent) -> Unit,
    onBack: () -> Unit,
) {
    var confirmLeave by remember { mutableStateOf(false) }
    val requestBack: () -> Unit = {
        when {
            state.saving -> Unit
            !state.loading && !state.routesLocked && !state.saved -> confirmLeave = true
            else -> onBack()
        }
    }
    BackHandler(enabled = state.saving || (!state.loading && !state.routesLocked && !state.saved)) { requestBack() }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.routes_unsaved_title)) },
            text = { Text(stringResource(R.string.routes_unsaved_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) {
                    Text(stringResource(R.string.routes_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    val capture = remember { LayoutCapture() }
    var addMenu by remember { mutableStateOf(false) }
    var pipeDialog by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    RouteDialogs(state, onIntent)
    if (helpOpen) {
        SchemaHelpSheet(onDismiss = { helpOpen = false })
    }
    if (pipeDialog) {
        PipeNameDialog(
            onDismiss = { pipeDialog = false },
            onConfirm = { name ->
                pipeDialog = false
                onIntent(RouteEditorIntent.AddPipe(name))
            },
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RouteTopBar(
                title = state.profileName,
                onBack = requestBack,
                backEnabled = !state.saving,
                onHelp = { helpOpen = true },
            )
        },
        floatingActionButton = {
            if (!state.routesLocked) {
                RouteAddMenu(
                    expanded = addMenu,
                    onExpanded = { addMenu = it },
                    showElse = !levelHasElse(state),
                    showPipe = !state.asList,
                    onAddRule = {
                        onIntent(RouteEditorIntent.AddChild(newRuleParent(state), elseRule = false))
                    },
                    onAddElse = {
                        onIntent(RouteEditorIntent.AddChild(newRuleParent(state), elseRule = true))
                    },
                    onAddPipe = { pipeDialog = true },
                )
            }
        },
        bottomBar = {
            if (!state.routesLocked) {
                RouteSaveBar(capture, state.saving, onIntent)
            }
        },
    ) { padding ->
        RouteBody(state, capture, onIntent, { helpOpen = true }, Modifier.padding(padding))
    }
}

@Composable
private fun RouteAddMenu(
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    showElse: Boolean,
    showPipe: Boolean,
    onAddRule: () -> Unit,
    onAddElse: () -> Unit,
    onAddPipe: () -> Unit,
) {
    Box {
        FloatingActionButton(onClick = { onExpanded(true) }) {
            Icon(LerNetSymbols.add(), contentDescription = stringResource(R.string.add_rule))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.canvas_add_rule)) },
                onClick = {
                    onExpanded(false)
                    onAddRule()
                },
            )
            if (showElse) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.canvas_add_else)) },
                    onClick = {
                        onExpanded(false)
                        onAddElse()
                    },
                )
            }
            if (showPipe) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.canvas_add_pipe)) },
                    onClick = {
                        onExpanded(false)
                        onAddPipe()
                    },
                )
            }
        }
    }
}

private fun newRuleParent(state: RouteEditorUiState): String? = RouteFolders.selectedParent(
    state.nodes,
    state.asList,
    state.listFolderId,
    state.canvasSelection,
)

private fun levelHasElse(state: RouteEditorUiState): Boolean {
    val parentId = newRuleParent(state)
    return state.nodes.any { it.parentId == parentId && it.isElseRule() }
}

@Composable
private fun RouteSaveBar(capture: LayoutCapture, saving: Boolean, onIntent: (RouteEditorIntent) -> Unit) {
    val saveLabel = stringResource(if (saving) R.string.saving else R.string.save)
    val bottomGap = rememberThumbZoneBottomPadding()
    Button(
        onClick = {
            if (capture.latest.isNotEmpty()) {
                onIntent(RouteEditorIntent.SetLayout(capture.latest))
            }
            onIntent(RouteEditorIntent.Save)
        },
        enabled = !saving,
        modifier = Modifier
            .padding(start = LerNetDimens.screenPadding, end = LerNetDimens.screenPadding, top = LerNetDimens.screenPadding)
            .padding(bottom = bottomGap)
            .lernetPrimaryAction()
            .semantics { contentDescription = saveLabel },
    ) {
        Text(saveLabel)
    }
}

@Composable
private fun RouteBody(
    state: RouteEditorUiState,
    capture: LayoutCapture,
    onIntent: (RouteEditorIntent) -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        RouteModeToggle(state.asList, capture, onIntent)
        if (state.routesLocked) {
            RoutesLockedCard(state, onIntent)
            return
        }
        OrphanHotbar(state, onIntent, Modifier.weight(1f)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.asList -> RouteFolderList(state, onIntent, Modifier.fillMaxSize())
                else -> RouteCanvas(state, capture, onIntent, onHelp, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun RoutesLockedCard(state: RouteEditorUiState, onIntent: (RouteEditorIntent) -> Unit) {
    val groupName = state.routingOwnerGroupName.orEmpty()
    Column(
        Modifier.padding(LerNetDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
    ) {
        Text(stringResource(R.string.routes_locked_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.routes_owned_by_group, groupName))
        Button(
            onClick = { onIntent(RouteEditorIntent.OpenOwnerGroup) },
            modifier = Modifier.fillMaxWidth().lernetButton(),
        ) {
            Text(stringResource(R.string.routes_open_group))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemaHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = LerNetDimens.contentPadding)
                .padding(bottom = LerNetDimens.sectionGap * 2)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Text(stringResource(R.string.canvas_help), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.canvas_help_root))
            Text(stringResource(R.string.canvas_help_rule))
            Text(stringResource(R.string.canvas_help_priority))
            Text(stringResource(R.string.canvas_help_pipe))
            Text(stringResource(R.string.canvas_help_edge))
            Text(stringResource(R.string.canvas_help_read))
            Text(stringResource(R.string.canvas_help_else))
            Text(stringResource(R.string.canvas_help_system))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).lernetButton()) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun RouteModeToggle(
    asList: Boolean,
    capture: LayoutCapture,
    onIntent: (RouteEditorIntent) -> Unit,
) {
    val modes = listOf(false, true)
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LerNetDimens.screenPadding, vertical = LerNetDimens.itemGap),
    ) {
        modes.forEachIndexed { index, listMode ->
            val selected = asList == listMode
            SegmentedButton(
                selected = selected,
                onClick = {
                    if (asList != listMode) {
                        if (capture.latest.isNotEmpty()) {
                            onIntent(RouteEditorIntent.SetLayout(capture.latest))
                        }
                        onIntent(RouteEditorIntent.ToggleList)
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                icon = {
                    if (selected) {
                        Icon(LerNetSymbols.check(), contentDescription = null)
                    }
                },
                label = {
                    Text(
                        if (listMode) {
                            stringResource(R.string.route_list)
                        } else {
                            stringResource(R.string.route_schema)
                        },
                    )
                },
                modifier = Modifier.lernetButton(),
            )
        }
    }
}

@Composable
private fun RouteDialogs(state: RouteEditorUiState, onIntent: (RouteEditorIntent) -> Unit) {
    val editing = state.nodes.firstOrNull { it.id == state.editingId }
    if (editing != null) {
        RuleEditorSheet(
            node = editing,
            nodes = state.nodes,
            namingNodeId = state.namingNodeId,
            fieldErrors = state.fieldErrors,
            rank = RouteFolders.priorityRank(state.nodes, editing),
            isNewDraft = editing.id in state.draftNodeIds,
            locked = state.routesLocked,
            onIntent = onIntent,
        )
    }
    if (state.applyPrompt) {
        AlertDialog(
            onDismissRequest = { onIntent(RouteEditorIntent.DismissApply) },
            title = { Text(stringResource(R.string.apply_routes_title)) },
            text = { Text(stringResource(R.string.apply_routes_body)) },
            confirmButton = {
                TextButton(onClick = { onIntent(RouteEditorIntent.ConfirmApply) }) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(RouteEditorIntent.DismissApply) }) {
                    Text(stringResource(R.string.later))
                }
            },
        )
    }
    val pending = state.pendingOutcome
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { onIntent(RouteEditorIntent.DismissOutcome) },
            title = { Text(stringResource(R.string.mode_change_title)) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(onClick = { onIntent(RouteEditorIntent.ConfirmOutcome) }) {
                    Text(stringResource(R.string.mode_change_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(RouteEditorIntent.DismissOutcome) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.confirmDeleteId != null) {
        DeleteNodeDialog(state, onIntent)
    }
    if (state.confirmEdge != null) {
        EdgeBreakDialog(state, onIntent)
    }
}

@Composable
private fun DeleteNodeDialog(state: RouteEditorUiState, onIntent: (RouteEditorIntent) -> Unit) {
    val id = state.confirmDeleteId ?: return
    val hasChildren = state.nodes.any { it.parentId == id }
    AlertDialog(
        onDismissRequest = { onIntent(RouteEditorIntent.DismissDelete) },
        title = {
            Text(stringResource(if (hasChildren) R.string.delete_rule_title else R.string.delete_else_title))
        },
        text = {
            Text(stringResource(if (hasChildren) R.string.delete_rule_body else R.string.delete_else_body))
        },
        confirmButton = {
            TextButton(onClick = { onIntent(RouteEditorIntent.ConfirmDelete) }) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(RouteEditorIntent.DismissDelete) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun EdgeBreakDialog(state: RouteEditorUiState, onIntent: (RouteEditorIntent) -> Unit) {
    val edge = state.confirmEdge ?: return
    val pipeName = pipeNameOf(state.nodes, edge)
    val elseEdge = edge.kind == SchemaEdgeKind.TREE
    AlertDialog(
        onDismissRequest = { onIntent(RouteEditorIntent.DismissBreakEdge) },
        title = {
            Text(stringResource(if (elseEdge) R.string.edge_else_title else R.string.edge_pipe_title))
        },
        text = {
            Text(
                if (elseEdge) {
                    stringResource(R.string.edge_else_body)
                } else {
                    stringResource(R.string.edge_pipe_body, pipeName)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onIntent(RouteEditorIntent.ConfirmBreakEdge) }) {
                Text(stringResource(R.string.edge_break))
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(RouteEditorIntent.DismissBreakEdge) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun pipeNameOf(nodes: List<RuleNodeRecord>, edge: SchemaEdge): String {
    val id = edge.fromId.takeIf(CanvasIds::isRule)?.let(CanvasIds::ruleKey) ?: return ""
    return nodes.firstOrNull { it.id == id }?.pipeName.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteTopBar(title: String, onBack: () -> Unit, backEnabled: Boolean, onHelp: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                title.ifBlank { stringResource(R.string.routes_title) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack, enabled = backEnabled) {
                Icon(LerNetSymbols.arrowBack(), contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            IconButton(onClick = onHelp) {
                Icon(LerNetSymbols.help(), contentDescription = stringResource(R.string.canvas_help))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorSheet(
    node: RuleNodeRecord,
    nodes: List<RuleNodeRecord>,
    namingNodeId: String?,
    fieldErrors: List<String>,
    rank: Int,
    isNewDraft: Boolean,
    locked: Boolean,
    onIntent: (RouteEditorIntent) -> Unit,
) {
    var confirmDiscard by remember(node.id) { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        if (RuleSheetDismiss.shouldDiscardDraft(node.shownConditions(), isNewDraft)) {
            confirmDiscard = true
        } else {
            onIntent(RouteEditorIntent.CloseEditor)
        }
    }
    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LerNetDimens.screenPadding, vertical = LerNetDimens.itemGap),
            verticalArrangement = Arrangement.spacedBy(LerNetDimens.itemGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rule_edit), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = requestClose) {
                    Icon(LerNetSymbols.close(), contentDescription = stringResource(R.string.close))
                }
            }
            RuleTitleField(node, onIntent)
            if (node.id in androidInactiveRuleIds(nodes)) {
                Text(stringResource(R.string.route_windows_only_explanation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PriorityControls(
                rank = rank,
                maxRank = nodes.count { it.parentId == node.parentId && !it.isElseRule() },
                nodeId = node.id,
                onIntent = onIntent,
                locked = locked || node.isElseRule(),
            )
            ActionChips(node, nodes, namingNodeId, onIntent)
            if (node.isElseRule()) {
                Text(stringResource(R.string.rule_else_locked))
            } else {
                RuleComposer(node, onIntent)
            }
            SheetFieldErrors(fieldErrors)
            RouteRuleExtras(node, nodes, namingNodeId, onIntent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onIntent(RouteEditorIntent.RequestDelete(node.id)) }) {
                    Icon(LerNetSymbols.delete(), contentDescription = null)
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = { onIntent(RouteEditorIntent.DoneEditor) }) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.rule_discard_title)) },
            text = { Text(stringResource(R.string.rule_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onIntent(RouteEditorIntent.CloseEditor)
                }) { Text(stringResource(R.string.rule_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SheetFieldErrors(errors: List<String>) {
    errors.forEach { raw ->
        Text(
            routeFieldErrorText(raw),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RuleTitleField(node: RuleNodeRecord, onIntent: (RouteEditorIntent) -> Unit) {
    if (node.isElseRule()) {
        Text(stringResource(R.string.rule_else_name), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = node.title,
            onValueChange = { onIntent(RouteEditorIntent.Update(node.copy(title = it))) },
            label = { Text(stringResource(R.string.field_rule_subtitle)) },
            supportingText = { Text(stringResource(R.string.field_rule_subtitle_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    OutlinedTextField(
        value = node.title,
        onValueChange = { onIntent(RouteEditorIntent.Update(node.copy(title = it))) },
        label = { Text(stringResource(R.string.field_rule_title)) },
        supportingText = { Text(stringResource(R.string.field_rule_title_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PipeNameDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pipe_name_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.route_pipe)) },
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
