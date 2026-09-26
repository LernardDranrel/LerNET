package app.lernet.ui.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lernet.config.parse.newId
import app.lernet.config.repo.ConfigRepository
import app.lernet.config.repo.RouteOwners
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.engine.ConnectionController
import app.lernet.engine.ConnectionState
import app.lernet.engine.redact.LerNetLog
import app.lernet.engine.toRuleNode
import app.lernet.routing.ConditionCodec
import app.lernet.routing.MatchJoin
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RouteElse
import app.lernet.routing.RouteTree
import app.lernet.routing.RuleConditions
import app.lernet.routing.RuleNode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RouteEditorUiState(
    val ownerId: String,
    val profileName: String = "",
    val nodes: List<RuleNodeRecord> = emptyList(),
    val fieldErrors: List<String> = emptyList(),
    val saved: Boolean = false,
    val saving: Boolean = false,
    val applyPrompt: Boolean = false,
    val loading: Boolean = true,
    val editingId: String? = null,
    val draftNodeIds: Set<String> = emptySet(),
    val confirmDeleteId: String? = null,
    val expandedIds: Set<String> = emptySet(),
    val asList: Boolean = false,
    val cycleRejected: Boolean = false,
    val terminalRejected: Boolean = false,
    val linkEpoch: Int = 0,
    val layout: Map<String, CanvasPoint> = emptyMap(),
    val extraPipes: List<String> = emptyList(),
    val canvasSelection: String? = null,
    val selectedEdge: SchemaEdge? = null,
    val confirmEdge: SchemaEdge? = null,
    val listFolderId: String? = null,
    val orphansOpen: Boolean = false,
    val placingOrphanId: String? = null,
    val pendingOutcome: PendingOutcome? = null,
    val namingNodeId: String? = null,
    val routesLocked: Boolean = false,
    val routingOwnerGroupName: String? = null,
    val routingOwnerGroupId: String? = null,
)

sealed class RouteEditorIntent {
    data class AddChild(val parentId: String?, val elseRule: Boolean = false) : RouteEditorIntent()

    data class RequestDelete(val id: String) : RouteEditorIntent()

    data object ConfirmDelete : RouteEditorIntent()

    data object DismissDelete : RouteEditorIntent()

    data class Toggle(val id: String) : RouteEditorIntent()

    data class Move(val id: String, val delta: Int) : RouteEditorIntent()

    data class Update(val node: RuleNodeRecord) : RouteEditorIntent()

    data class Duplicate(val id: String) : RouteEditorIntent()

    data class Edit(val id: String) : RouteEditorIntent()

    data object CloseEditor : RouteEditorIntent()

    /** Done in the sheet: validate conditions; stay open if the gate fails. */
    data object DoneEditor : RouteEditorIntent()

    data class ToggleExpand(val id: String) : RouteEditorIntent()

    data object Save : RouteEditorIntent()

    data object ConfirmApply : RouteEditorIntent()

    data object DismissApply : RouteEditorIntent()

    data object RepairElse : RouteEditorIntent()

    data object ToggleList : RouteEditorIntent()

    data class Reparent(val id: String, val parentId: String?) : RouteEditorIntent()

    data class SetLayout(val layout: Map<String, CanvasPoint>) : RouteEditorIntent()

    data class ApplyGraph(
        val nodes: List<RuleNodeRecord>,
        val rejectedCycle: Boolean,
        val rejectedTerminal: Boolean,
    ) : RouteEditorIntent()

    data class AddPipe(val name: String) : RouteEditorIntent()

    data class RemovePipe(val name: String) : RouteEditorIntent()

    data class ReorderSiblings(val parentId: String?, val orderedIds: List<String>) : RouteEditorIntent()

    data class SelectCanvas(val id: String?) : RouteEditorIntent()

    data class SelectEdge(val edge: SchemaEdge?) : RouteEditorIntent()

    data class RequestBreakEdge(val edge: SchemaEdge) : RouteEditorIntent()

    data object ConfirmBreakEdge : RouteEditorIntent()

    data object DismissBreakEdge : RouteEditorIntent()

    data object OpenOwnerGroup : RouteEditorIntent()

    data class OpenFolder(val id: String) : RouteEditorIntent()

    data object FolderBack : RouteEditorIntent()

    data class Detach(val id: String) : RouteEditorIntent()

    data class SetOrphansOpen(val open: Boolean) : RouteEditorIntent()

    data class PlaceOrphan(val id: String) : RouteEditorIntent()

    data class ToggleAxis(val column: Float, val row: Float) : RouteEditorIntent()

    data class RequestOutcome(val id: String, val choice: OutcomeChoice) : RouteEditorIntent()

    data object ConfirmOutcome : RouteEditorIntent()

    data object DismissOutcome : RouteEditorIntent()
}

enum class OutcomeChoice {
    AUTO,
    NAMED,
    FORK,
    DIRECT,
    BLOCK,
}

data class PendingOutcome(val nodeId: String, val choice: OutcomeChoice, val message: String)

sealed class RouteEditorEvent {
    data object Leave : RouteEditorEvent()

    data class OpenGroupRoutes(val groupId: String) : RouteEditorEvent()
}

@HiltViewModel
class RouteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ConfigRepository,
    private val controller: ConnectionController,
) : ViewModel() {
    private val navOwnerId: String = checkNotNull(savedStateHandle["profileId"])
    private val _state = MutableStateFlow(RouteEditorUiState(ownerId = navOwnerId))
    val state: StateFlow<RouteEditorUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<RouteEditorEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<RouteEditorEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onIntent(intent: RouteEditorIntent) {
        when (intent) {
            is RouteEditorIntent.AddChild -> addChild(intent.parentId, intent.elseRule)
            is RouteEditorIntent.RequestDelete,
            RouteEditorIntent.ConfirmDelete,
            RouteEditorIntent.DismissDelete,
            -> onDeleteIntent(intent)
            is RouteEditorIntent.Toggle,
            is RouteEditorIntent.Move,
            is RouteEditorIntent.Update,
            is RouteEditorIntent.Duplicate,
            -> onMutateIntent(intent)
            is RouteEditorIntent.Edit,
            RouteEditorIntent.CloseEditor,
            RouteEditorIntent.DoneEditor,
            is RouteEditorIntent.ToggleExpand,
            RouteEditorIntent.ToggleList,
            is RouteEditorIntent.Reparent,
            -> onEditorIntent(intent)
            RouteEditorIntent.Save,
            RouteEditorIntent.ConfirmApply,
            RouteEditorIntent.DismissApply,
            RouteEditorIntent.RepairElse,
            -> onApplyIntent(intent)
            is RouteEditorIntent.SetLayout -> _state.update { state ->
                val validIds = buildSet {
                    add(CanvasIds.AXIS)
                    add(CanvasIds.ROOT)
                    add(CanvasIds.SYSTEM)
                    RouteFolders.attached(state.nodes).forEach { add(CanvasIds.rule(it.id)) }
                    CanvasGraph.pipeNames(state.nodes, state.extraPipes).forEach { add(CanvasIds.pipe(it)) }
                }
                val savedPipes = state.layout.filterKeys { id ->
                    CanvasIds.isPipe(id) && CanvasIds.pipeKey(id) in state.extraPipes
                }
                state.copy(layout = savedPipes + intent.layout.filterKeys { it in validIds })
            }
            is RouteEditorIntent.ApplyGraph -> applyGraph(intent)
            is RouteEditorIntent.AddPipe -> addPipe(intent.name)
            is RouteEditorIntent.RemovePipe -> removePipe(intent.name)
            is RouteEditorIntent.ReorderSiblings -> reorderSiblings(intent.parentId, intent.orderedIds)
            is RouteEditorIntent.SelectCanvas -> _state.update { it.copy(canvasSelection = intent.id) }
            is RouteEditorIntent.SelectEdge,
            is RouteEditorIntent.RequestBreakEdge,
            RouteEditorIntent.ConfirmBreakEdge,
            RouteEditorIntent.DismissBreakEdge,
            -> onEdgeIntent(intent)
            RouteEditorIntent.OpenOwnerGroup -> openOwnerGroup()
            is RouteEditorIntent.OpenFolder,
            RouteEditorIntent.FolderBack,
            is RouteEditorIntent.Detach,
            is RouteEditorIntent.SetOrphansOpen,
            is RouteEditorIntent.PlaceOrphan,
            is RouteEditorIntent.ToggleAxis,
            -> onFolderIntent(intent)
            is RouteEditorIntent.RequestOutcome,
            RouteEditorIntent.ConfirmOutcome,
            RouteEditorIntent.DismissOutcome,
            -> onOutcomeIntent(intent)
        }
    }

    private fun onApplyIntent(intent: RouteEditorIntent) {
        when (intent) {
            RouteEditorIntent.Save -> save()
            RouteEditorIntent.ConfirmApply -> applyConnected()
            RouteEditorIntent.DismissApply -> dismissApply()
            RouteEditorIntent.RepairElse -> repairElse()
            else -> error("not an apply intent: $intent")
        }
    }

    private fun onOutcomeIntent(intent: RouteEditorIntent) {
        when (intent) {
            is RouteEditorIntent.RequestOutcome -> requestOutcome(intent.id, intent.choice)
            RouteEditorIntent.ConfirmOutcome -> confirmOutcome()
            RouteEditorIntent.DismissOutcome -> _state.update { it.copy(pendingOutcome = null) }
            else -> error("not an outcome intent: $intent")
        }
    }

    private fun onEdgeIntent(intent: RouteEditorIntent) {
        when (intent) {
            is RouteEditorIntent.SelectEdge -> _state.update { it.copy(selectedEdge = intent.edge) }
            is RouteEditorIntent.RequestBreakEdge -> requestBreakEdge(intent.edge)
            RouteEditorIntent.ConfirmBreakEdge -> confirmBreakEdge()
            RouteEditorIntent.DismissBreakEdge -> _state.update { it.copy(confirmEdge = null) }
            else -> error("not an edge intent: $intent")
        }
    }

    private fun onFolderIntent(intent: RouteEditorIntent) {
        when (intent) {
            is RouteEditorIntent.OpenFolder -> openFolder(intent.id)
            RouteEditorIntent.FolderBack -> folderBack()
            is RouteEditorIntent.Detach -> detach(intent.id)
            is RouteEditorIntent.SetOrphansOpen -> _state.update { it.copy(orphansOpen = intent.open) }
            is RouteEditorIntent.PlaceOrphan -> dropOrphan(intent.id)
            is RouteEditorIntent.ToggleAxis -> toggleAxis(intent.column, intent.row)
            else -> error("not a folder intent: $intent")
        }
    }

    private suspend fun load() {
        if (RouteOwners.isGroup(navOwnerId)) {
            publishLoaded(repository.ensureDefaultElse(navOwnerId))
            return
        }
        val (_, ownerGroup) = repository.routingOwnerForProfile(navOwnerId)
        if (ownerGroup != null) {
            _state.update {
                it.copy(
                    ownerId = navOwnerId,
                    profileName = repository.ownerDisplayName(navOwnerId),
                    loading = false,
                    routesLocked = true,
                    routingOwnerGroupName = ownerGroup.name,
                    routingOwnerGroupId = ownerGroup.id,
                    nodes = emptyList(),
                )
            }
            return
        }
        publishLoaded(repository.ensureDefaultElse(navOwnerId))
    }

    private suspend fun publishLoaded(loaded: List<RuleNodeRecord>) {
        val seeded = RouteFolders.seedMissingElse(loaded, navOwnerId, ::newId)
        val roots = seeded.filter { it.parentId == null }.map { it.id }
        val layout = CanvasGraph.decode(repository.readCanvasLayout(navOwnerId))
        _state.update {
            it.copy(
                ownerId = navOwnerId,
                profileName = repository.ownerDisplayName(navOwnerId),
                nodes = seeded,
                draftNodeIds = emptySet(),
                loading = false,
                expandedIds = roots.toSet(),
                layout = layout,
                extraPipes = CanvasGraph.savedPipeNames(layout),
                routesLocked = false,
                routingOwnerGroupName = null,
                routingOwnerGroupId = null,
                saved = seeded == loaded,
            )
        }
    }

    private fun elseParents(nodes: List<RuleNodeRecord>, doomed: Set<String>): Set<String?> =
        nodes.filter { node ->
            node.id in doomed && node.isElseRule() && node.parentId !in doomed
        }.map { it.parentId }.toSet()

    private fun openOwnerGroup() {
        val groupId = _state.value.routingOwnerGroupId ?: return
        viewModelScope.launch {
            _events.emit(RouteEditorEvent.OpenGroupRoutes(groupId))
        }
    }

    private fun onDeleteIntent(intent: RouteEditorIntent) {
        when (intent) {
            is RouteEditorIntent.RequestDelete -> requestDelete(intent.id)
            RouteEditorIntent.ConfirmDelete -> {
                val id = _state.value.confirmDeleteId ?: return
                removeNode(id)
                _state.update { it.copy(confirmDeleteId = null) }
            }
            RouteEditorIntent.DismissDelete -> _state.update { it.copy(confirmDeleteId = null) }
            else -> error("not a delete intent: $intent")
        }
    }

    private fun onMutateIntent(intent: RouteEditorIntent) {
        when (intent) {
            is RouteEditorIntent.Toggle ->
                _state.update {
                    it.copy(
                        nodes = it.nodes.map { n -> if (n.id == intent.id && !n.isElseRule()) n.copy(enabled = !n.enabled) else n },
                        saved = false,
                    )
                }
            is RouteEditorIntent.Move -> move(intent.id, intent.delta)
            is RouteEditorIntent.Update ->
                _state.update {
                    val next = it.nodes.map { n -> if (n.id == intent.node.id) intent.node else n }
                    it.copy(
                        nodes = RouteFolders.seedMissingElse(next, it.ownerId, ::newId),
                        saved = false,
                        fieldErrors = emptyList(),
                    )
                }
            is RouteEditorIntent.Duplicate -> duplicate(intent.id)
            else -> error("not a mutate intent: $intent")
        }
    }

    private fun closeEditor() {
        val state = _state.value
        val id = state.editingId ?: return
        val node = state.nodes.firstOrNull { it.id == id }
        if (node == null || node.isElseRule()) {
            _state.update { it.copy(editingId = null, fieldErrors = emptyList()) }
            return
        }
        // Always clear editingId on dismiss so ModalBottomSheet leaves the tree.
        // Keeping it after a swipe-dismiss leaves a Hidden sheet that eats touches.
        if (RuleSheetDismiss.shouldDiscardDraft(node.shownConditions(), id in state.draftNodeIds)) {
            discardIncompleteDraft(id)
        } else {
            _state.update { it.copy(editingId = null, fieldErrors = emptyList()) }
        }
    }

    private fun doneEditor() {
        val state = _state.value
        val id = state.editingId ?: return
        val node = state.nodes.firstOrNull { it.id == id }
        if (node == null || node.isElseRule()) {
            _state.update { it.copy(editingId = null, fieldErrors = emptyList()) }
            return
        }
        val errors = RuleSheetGate.errors(node.shownConditions())
        if (errors.isEmpty()) {
            _state.update {
                it.copy(
                    editingId = null,
                    draftNodeIds = it.draftNodeIds - id,
                    fieldErrors = emptyList(),
                )
            }
        } else {
            _state.update { it.copy(fieldErrors = errors) }
        }
    }

    private fun discardIncompleteDraft(id: String) {
        val doomed = (descendants(_state.value.nodes, id) + id).toSet()
        val reseed = elseParents(_state.value.nodes, doomed)
        _state.update {
            val kept = it.nodes.filterNot { n -> n.id in doomed }
            it.copy(
                nodes = RouteFolders.seedMissingElse(kept, it.ownerId, ::newId, reseed),
                draftNodeIds = it.draftNodeIds - doomed,
                editingId = null,
                fieldErrors = emptyList(),
                listFolderId = it.listFolderId?.takeUnless { folder -> folder in doomed },
                placingOrphanId = it.placingOrphanId?.takeUnless { placed -> placed in doomed },
                canvasSelection = it.canvasSelection?.takeUnless { sel ->
                    sel == CanvasIds.rule(id) || doomed.any { d -> sel == CanvasIds.rule(d) }
                },
                saved = false,
            )
        }
    }

    private fun onEditorIntent(intent: RouteEditorIntent) {
        when (intent) {
            is RouteEditorIntent.Edit -> _state.update { it.copy(editingId = intent.id, fieldErrors = emptyList()) }
            RouteEditorIntent.CloseEditor -> closeEditor()
            RouteEditorIntent.DoneEditor -> doneEditor()
            is RouteEditorIntent.ToggleExpand ->
                _state.update {
                    val next = if (intent.id in it.expandedIds) it.expandedIds - intent.id else it.expandedIds + intent.id
                    it.copy(expandedIds = next)
                }
            RouteEditorIntent.ToggleList -> _state.update { it.copy(asList = !it.asList) }
            is RouteEditorIntent.Reparent -> reparent(intent.id, intent.parentId)
            else -> error("not an editor intent: $intent")
        }
    }

    private fun requestDelete(id: String) {
        if (RouteFolders.deleteNeedsConfirm(_state.value.nodes, id)) {
            _state.update { it.copy(confirmDeleteId = id) }
        } else {
            removeNode(id)
        }
    }

    private fun removeNode(id: String) {
        val doomed = (descendants(_state.value.nodes, id) + id).toSet()
        _state.update {
            val kept = RouteFolders.restoreTerminalAfterElseDeletion(it.nodes, id)
                .filterNot { n -> n.id in doomed }
            it.copy(
                nodes = RouteFolders.seedMissingElse(kept, it.ownerId, ::newId),
                draftNodeIds = it.draftNodeIds - doomed,
                editingId = it.editingId?.takeUnless { edit -> edit in doomed },
                listFolderId = it.listFolderId?.takeUnless { folder -> folder in doomed },
                placingOrphanId = it.placingOrphanId?.takeUnless { placed -> placed in doomed },
                saved = false,
            )
        }
    }

    private fun addChild(parentId: String?, elseRule: Boolean) {
        if (_state.value.routesLocked) return
        val parent = _state.value.nodes.firstOrNull { it.id == parentId }
        if (parentId != null && parent == null) {
            _state.update { it.copy(terminalRejected = true, cycleRejected = false) }
            return
        }
        val ownerId = _state.value.ownerId
        val siblings = _state.value.nodes.filter { it.parentId == parentId }
        if (elseRule && siblings.any { it.isElseRule() }) {
            _state.update { it.copy(fieldErrors = listOf("else: ${RouteElse.EXTRA}")) }
            return
        }
        val sortIndex = if (elseRule) {
            siblings.size
        } else {
            siblings.count { !it.isElseRule() }
        }
        val node = RuleNodeRecord(
            id = newId(),
            profileId = ownerId,
            parentId = parentId,
            enabled = true,
            sortIndex = sortIndex,
            action = if (elseRule) parent?.action ?: "proxy" else RouteAction.PROXY.name.lowercase(),
            apps = emptyList(),
            domains = emptyList(),
            domainSuffixes = emptyList(),
            ipCidrs = emptyList(),
            geoip = emptyList(),
            pipeName = if (elseRule) parent?.pipeName.orEmpty() else "",
            blocksJson = if (elseRule) {
                ""
            } else {
                ConditionCodec.encode(RuleConditions(MatchJoin.OR, emptyList()))
            },
        )
        _state.update {
            val branched = if (elseRule) {
                it.nodes.map { item -> if (item.id == parentId) item.copy(pipeName = "") else item }
            } else {
                RouteFolders.branchPreservingOutcome(it.nodes, parentId, ::newId)
            }
            it.copy(
                nodes = RouteFolders.seedMissingElse(branched + node, it.ownerId, ::newId),
                draftNodeIds = if (elseRule) it.draftNodeIds else it.draftNodeIds + node.id,
                saved = false,
                editingId = node.id.takeUnless { elseRule },
                canvasSelection = CanvasIds.rule(node.id),
                terminalRejected = false,
                fieldErrors = emptyList(),
                expandedIds = if (parentId != null) it.expandedIds + parentId else it.expandedIds,
            )
        }
    }

    private fun duplicate(id: String) {
        if (_state.value.routesLocked) return
        val src = _state.value.nodes.firstOrNull { it.id == id } ?: return
        if (src.isElseRule()) {
            _state.update { it.copy(fieldErrors = listOf("else: ${RouteElse.EXTRA}")) }
            return
        }
        val siblings = _state.value.nodes.filter { it.parentId == src.parentId }
        val copy = src.copy(
            id = newId(),
            sortIndex = (siblings.maxOfOrNull { it.sortIndex } ?: -1) + 1,
        )
        _state.update {
            it.copy(
                nodes = RouteFolders.seedMissingElse(it.nodes + copy, it.ownerId, ::newId),
                draftNodeIds = it.draftNodeIds + copy.id,
                saved = false,
                editingId = copy.id,
                fieldErrors = emptyList(),
            )
        }
    }

    private fun move(id: String, delta: Int) {
        if (_state.value.routesLocked) return
        val nodes = _state.value.nodes
        val current = nodes.firstOrNull { it.id == id } ?: return
        val siblings = RouteFolders.children(nodes, current.parentId)
        val index = siblings.indexOfFirst { it.id == id }
        val swapWith = siblings.getOrNull(index + delta)
        if (current.isElseRule() || swapWith == null || swapWith.isElseRule()) return
        val updated = nodes.map { node ->
            when (node.id) {
                current.id -> node.copy(sortIndex = swapWith.sortIndex)
                swapWith.id -> node.copy(sortIndex = current.sortIndex)
                else -> node
            }
        }
        _state.update { it.copy(nodes = RouteFolders.pinElseLast(updated), saved = false) }
    }

    private fun reparent(id: String, parentId: String?) {
        if (_state.value.routesLocked) return
        if (rejectsChildren(parentId)) {
            _state.update { it.copy(terminalRejected = true, cycleRejected = false) }
            return
        }
        val nodes = _state.value.nodes
        val parents = nodes.associate { it.id to it.parentId }
        if (RouteTree.wouldCycle(parents, id, parentId)) {
            _state.update { it.copy(cycleRejected = true, terminalRejected = false) }
            return
        }
        commitReparent(nodes, id, parentId)
    }

    private fun commitReparent(nodes: List<RuleNodeRecord>, id: String, parentId: String?) {
        val moving = nodes.firstOrNull { it.id == id } ?: return
        if (elseParentTaken(nodes, moving, parentId)) {
            _state.update { it.copy(fieldErrors = listOf("else: ${RouteElse.EXTRA}")) }
            return
        }
        val siblings = nodes.filter { it.parentId == parentId && it.id != id }
        val sort = (siblings.maxOfOrNull { it.sortIndex } ?: -1) + 1
        val extra = if (moving.isElseRule()) setOf(moving.parentId) else emptySet()
        _state.update {
            val moved = it.nodes.map { node ->
                if (node.id == id) node.copy(parentId = parentId, sortIndex = sort) else node
            }
            it.copy(
                nodes = RouteFolders.seedMissingElse(moved, it.ownerId, ::newId, extra),
                saved = false,
                cycleRejected = false,
                terminalRejected = false,
                fieldErrors = emptyList(),
                expandedIds = if (parentId != null) it.expandedIds + parentId else it.expandedIds,
            )
        }
    }

    private fun elseParentTaken(
        nodes: List<RuleNodeRecord>,
        moving: RuleNodeRecord,
        parentId: String?,
    ): Boolean {
        if (!moving.isElseRule()) return false
        return nodes.any { it.parentId == parentId && it.id != moving.id && it.isElseRule() }
    }

    private fun save() {
        if (_state.value.routesLocked || _state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val ownerId = _state.value.ownerId
                val ordered = _state.value.nodes.map { it.copy(profileId = ownerId) }
                val incomplete = ordered.filterNot { it.isElseRule() }.firstOrNull { node ->
                    RuleSheetGate.isIncomplete(node.shownConditions())
                }
                if (incomplete != null) {
                    _state.update {
                        it.copy(
                            nodes = ordered,
                            editingId = incomplete.id,
                            fieldErrors = RuleSheetGate.errors(incomplete.shownConditions()),
                        )
                    }
                    return@launch
                }
                val compiled = withContext(Dispatchers.Default) { RouteCompiler.compile(ordered.toRouting()) }
                if (!compiled.isValid) {
                    _state.update { it.copy(fieldErrors = compiled.errors.map { e -> "${e.field}: ${e.message}" }, nodes = ordered) }
                    return@launch
                }
                val layout = _state.value.layout
                repository.replaceRuleNodes(ownerId, ordered)
                repository.writeCanvasLayout(ownerId, CanvasGraph.encode(layout))
                val unchanged = _state.value.nodes == ordered && _state.value.layout == layout
                val activeId = controller.snapshot.value.activeProfileId
                val prompt = unchanged &&
                    !RouteOwners.isGroup(ownerId) &&
                    (
                        controller.snapshot.value.state == ConnectionState.CONNECTED ||
                            controller.snapshot.value.state == ConnectionState.RECONNECTING
                        ) &&
                    activeId == ownerId
                _state.update {
                    it.copy(
                        fieldErrors = emptyList(),
                        saved = unchanged,
                        applyPrompt = prompt,
                        draftNodeIds = it.draftNodeIds - ordered.map { node -> node.id }.toSet(),
                    )
                }
                if (unchanged && !prompt) _events.emit(RouteEditorEvent.Leave)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LerNetLog.e("LerNet.RouteEditor", "save failed", error)
                _state.update { it.copy(fieldErrors = listOf("save: Не удалось сохранить маршруты. Изменения остались в редакторе.")) }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    private fun applyConnected() {
        viewModelScope.launch {
            val ownerId = _state.value.ownerId
            if (RouteOwners.isGroup(ownerId)) {
                _state.update { it.copy(applyPrompt = false) }
                _events.emit(RouteEditorEvent.Leave)
                return@launch
            }
            val profile = repository.getProfile(ownerId) ?: return@launch
            controller.connect(profile, _state.value.nodes, controller.snapshot.value.mode)
            _state.update { it.copy(applyPrompt = false) }
            _events.emit(RouteEditorEvent.Leave)
        }
    }

    private fun dismissApply() {
        _state.update { it.copy(applyPrompt = false) }
        viewModelScope.launch { _events.emit(RouteEditorEvent.Leave) }
    }

    private fun addPipe(raw: String) {
        if (_state.value.routesLocked) return
        val name = raw.trim()
        if (name.isEmpty()) return
        val selected = _state.value.canvasSelection?.let { id ->
            if (CanvasIds.isRule(id)) CanvasIds.ruleKey(id) else null
        }
        _state.update { state ->
            val pipes = (state.extraPipes + name).distinct()
            val key = CanvasIds.pipe(name)
            val layout = if (key in state.layout) {
                state.layout
            } else {
                state.layout +
                    (key to CanvasPoint(620f, 36f + 170f * (pipes.size - 1)))
            }
            if (selected == null) {
                state.copy(extraPipes = pipes, layout = layout, saved = false)
            } else {
                state.copy(
                    nodes = state.nodes.map { node -> if (node.id == selected) node.copy(pipeName = name) else node },
                    extraPipes = pipes,
                    layout = layout,
                    saved = false,
                )
            }
        }
    }

    private fun removePipe(name: String) {
        _state.update { state ->
            if (state.nodes.any { it.pipeName == name }) return@update state
            state.copy(
                extraPipes = state.extraPipes - name,
                layout = state.layout - CanvasIds.pipe(name),
                saved = false,
            )
        }
    }

    private fun dropOrphan(id: String) {
        val state = _state.value
        if (state.routesLocked) return
        val node = state.nodes.firstOrNull { it.id == id && it.parentId == RouteFolders.ORPHAN } ?: return
        val parent = RouteFolders.selectedParent(state.nodes, state.asList, state.listFolderId, state.canvasSelection)
        if (elseAlreadyAt(state.nodes, node, parent)) {
            _state.update { it.copy(fieldErrors = listOf("else: ${RouteElse.EXTRA}"), orphansOpen = false) }
            return
        }
        _state.update {
            it.copy(
                nodes = RouteFolders.adopt(state.nodes, id, parent, state.ownerId, ::newId),
                placingOrphanId = null,
                orphansOpen = false,
                saved = false,
                terminalRejected = false,
                fieldErrors = emptyList(),
            )
        }
    }

    private fun elseAlreadyAt(nodes: List<RuleNodeRecord>, node: RuleNodeRecord, parent: String?): Boolean =
        node.isElseRule() && nodes.any { it.parentId == parent && it.isElseRule() }

    private fun reorderSiblings(parentId: String?, orderedIds: List<String>) {
        if (_state.value.routesLocked) return
        if (rejectsChildren(parentId)) {
            _state.update { it.copy(terminalRejected = true, cycleRejected = false) }
            return
        }
        val nodes = _state.value.nodes
        val indexOf = orderedIds.withIndex().associate { it.value to it.index }
        val placing = _state.value.placingOrphanId
        val placingNode = placing?.let { id -> nodes.firstOrNull { it.id == id } }
        val clash = placingNode != null &&
            placing in indexOf &&
            placingNode.isElseRule() &&
            nodes.any { it.parentId == parentId && it.id != placing && it.isElseRule() }
        if (clash) {
            _state.update { it.copy(fieldErrors = listOf("else: ${RouteElse.EXTRA}")) }
            return
        }
        _state.update { state ->
            val adopted = placing != null && placing in indexOf
            val moved = state.nodes.map { node ->
                val index = indexOf[node.id]
                val inFolder = node.parentId == parentId || node.id == placing
                if (index != null && inFolder) node.copy(parentId = parentId, sortIndex = index) else node
            }
            state.copy(
                nodes = RouteFolders.seedMissingElse(moved, state.ownerId, ::newId),
                placingOrphanId = if (adopted) null else state.placingOrphanId,
                saved = false,
                terminalRejected = false,
                fieldErrors = emptyList(),
            )
        }
    }

    private fun openFolder(id: String) {
        val node = _state.value.nodes.firstOrNull { it.id == id } ?: return
        if (node.acceptsChildren(_state.value.nodes)) {
            _state.update { it.copy(listFolderId = id) }
        } else {
            _state.update { it.copy(editingId = id) }
        }
    }

    private fun applyGraph(intent: RouteEditorIntent.ApplyGraph) {
        _state.update {
            val pinned = RouteFolders.seedMissingElse(intent.nodes, it.ownerId, ::newId)
            val changed = pinned != it.nodes
            it.copy(
                nodes = pinned,
                draftNodeIds = it.draftNodeIds.intersect(pinned.map { node -> node.id }.toSet()),
                saved = if (changed) false else it.saved,
                cycleRejected = intent.rejectedCycle,
                terminalRejected = intent.rejectedTerminal,
                linkEpoch = if (intent.rejectedTerminal || intent.rejectedCycle) it.linkEpoch + 1 else it.linkEpoch,
            )
        }
    }

    private fun rejectsChildren(parentId: String?): Boolean {
        if (parentId == null) return false
        val parent = _state.value.nodes.firstOrNull { it.id == parentId } ?: return true
        return !parent.canAdoptChild()
    }

    private fun requestOutcome(id: String, choice: OutcomeChoice) {
        if (_state.value.routesLocked) return
        val node = _state.value.nodes.firstOrNull { it.id == id } ?: return
        val loss = outcomeLoss(node, choice)
        if (loss == null) {
            applyOutcome(id, choice)
        } else {
            _state.update { it.copy(pendingOutcome = PendingOutcome(id, choice, loss)) }
        }
    }

    private fun outcomeLoss(node: RuleNodeRecord, choice: OutcomeChoice): String? {
        val kids = _state.value.nodes.any { it.parentId == node.id }
        val named = node.pipeName.isNotBlank()
        val dropKids = kids && choice != OutcomeChoice.FORK
        val dropPipe = named && choice != OutcomeChoice.NAMED && choice != OutcomeChoice.FORK
        return when {
            dropKids && dropPipe -> RouteElse.LOSS_BOTH
            dropKids -> RouteElse.LOSS_CHILDREN
            dropPipe -> RouteElse.LOSS_PIPE
            else -> null
        }
    }

    private fun confirmOutcome() {
        val pending = _state.value.pendingOutcome ?: return
        applyOutcome(pending.nodeId, pending.choice)
    }

    private fun applyOutcome(id: String, choice: OutcomeChoice) {
        val state = _state.value
        val node = state.nodes.firstOrNull { it.id == id } ?: return
        val dropKids = choice != OutcomeChoice.FORK
        val doomed = if (dropKids) descendants(state.nodes, id) else emptySet()
        val base = if (choice == OutcomeChoice.FORK) {
            RouteFolders.branchPreservingOutcome(state.nodes, id, ::newId)
        } else {
            state.nodes
        }
        val edited = node.withChoice(choice)
        val kept = base.filterNot { it.id in doomed }.map { if (it.id == id) edited else it }
        val withElse = if (choice == OutcomeChoice.FORK) {
            RouteFolders.seedMissingElse(kept, state.ownerId, ::newId, setOf(id))
        } else {
            RouteFolders.pinElseLast(kept)
        }
        val naming = if (choice == OutcomeChoice.NAMED && edited.pipeName.isBlank()) id else null
        _state.update {
            it.copy(
                nodes = withElse,
                draftNodeIds = it.draftNodeIds - doomed,
                saved = false,
                pendingOutcome = null,
                namingNodeId = naming,
                fieldErrors = emptyList(),
                terminalRejected = false,
                editingId = it.editingId?.takeUnless { edit -> edit in doomed },
                listFolderId = it.listFolderId?.takeUnless { folder -> folder in doomed },
            )
        }
    }

    private fun repairElse() {
        if (_state.value.routesLocked) return
        _state.update { state ->
            val seeded = RouteFolders.seedMissingElse(state.nodes, state.ownerId, ::newId)
            val ordered = seeded.map { it.copy(profileId = state.ownerId) }
            val compiled = RouteCompiler.compile(ordered.toRouting())
            val errors = if (compiled.isValid) {
                emptyList()
            } else {
                compiled.errors.map { error -> "${error.field}: ${error.message}" }
            }
            state.copy(nodes = ordered, fieldErrors = errors, saved = false)
        }
    }

    private fun folderBack() {
        val id = _state.value.listFolderId ?: return
        val parent = _state.value.nodes.firstOrNull { it.id == id }?.parentId
        val next = parent?.takeUnless { it == RouteFolders.ORPHAN }
        _state.update { it.copy(listFolderId = next) }
    }

    private fun requestBreakEdge(edge: SchemaEdge) {
        if (_state.value.routesLocked) return
        when (SchemaEdges.confirmBeforeBreak(_state.value.nodes, edge)) {
            EdgeConfirm.NONE -> applyBreak(edge)
            EdgeConfirm.ELSE, EdgeConfirm.PIPE -> _state.update {
                it.copy(confirmEdge = edge, selectedEdge = edge)
            }
        }
    }

    private fun confirmBreakEdge() {
        val edge = _state.value.confirmEdge ?: return
        applyBreak(edge)
    }

    private fun applyBreak(edge: SchemaEdge) {
        when (edge.kind) {
            SchemaEdgeKind.TREE -> {
                val id = edge.toId.takeIf(CanvasIds::isRule)?.let(CanvasIds::ruleKey) ?: return
                detach(id)
            }
            SchemaEdgeKind.PIPE -> releasePipe(edge)
        }
    }

    private fun releasePipe(edge: SchemaEdge) {
        if (_state.value.routesLocked) return
        val id = edge.fromId.takeIf(CanvasIds::isRule)?.let(CanvasIds::ruleKey) ?: return
        _state.update { state ->
            val name = state.nodes.firstOrNull { it.id == id }?.pipeName?.trim().orEmpty()
            val pipes = if (name.isEmpty()) state.extraPipes else (state.extraPipes + name).distinct()
            state.copy(
                nodes = SchemaEdges.afterPipeBreak(state.nodes, id),
                extraPipes = pipes,
                selectedEdge = null,
                confirmEdge = null,
                saved = false,
            )
        }
    }

    private fun detach(id: String) {
        if (_state.value.routesLocked) return
        _state.update { state ->
            val leaving = state.listFolderId == id
            val parent = state.nodes.firstOrNull { it.id == id }?.parentId
            state.copy(
                nodes = SchemaEdges.afterTreeBreak(state.nodes, state.ownerId, id, ::newId),
                placingOrphanId = state.placingOrphanId?.takeUnless { it == id },
                listFolderId = if (leaving) parent?.takeUnless { it == RouteFolders.ORPHAN } else state.listFolderId,
                selectedEdge = null,
                confirmEdge = null,
                saved = false,
            )
        }
    }

    private fun toggleAxis(column: Float, row: Float) {
        if (_state.value.routesLocked) return
        val vertical = !CanvasGraph.isVertical(_state.value.layout)
        val pipes = CanvasGraph.pipeNames(_state.value.nodes, _state.value.extraPipes)
        val axisOnly = mapOf(CanvasIds.AXIS to CanvasPoint(if (vertical) 1f else 0f, 0f))
        val next = CanvasGraph.layout(_state.value.nodes, pipes, axisOnly, column, row)
        _state.update { it.copy(layout = next, saved = false) }
    }

    private fun List<RuleNodeRecord>.toRouting(): List<RuleNode> = map { it.toRuleNode() }
}

private fun RuleNodeRecord.withChoice(choice: OutcomeChoice): RuleNodeRecord = when (choice) {
    OutcomeChoice.AUTO -> copy(action = "proxy", pipeName = "")
    OutcomeChoice.NAMED -> copy(action = "proxy")
    OutcomeChoice.FORK -> copy(pipeName = "")
    OutcomeChoice.DIRECT -> copy(action = "direct", pipeName = "")
    OutcomeChoice.BLOCK -> copy(action = "block", pipeName = "")
}

internal fun descendants(nodes: List<RuleNodeRecord>, id: String): Set<String> {
    val kids = nodes.filter { it.parentId == id }.map { it.id }
    return kids.toSet() + kids.flatMap { descendants(nodes, it) }
}
