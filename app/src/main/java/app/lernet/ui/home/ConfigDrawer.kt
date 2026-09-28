package app.lernet.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import app.lernet.R
import app.lernet.config.model.Group
import app.lernet.config.model.Profile
import app.lernet.config.model.ProfileSource
import app.lernet.ui.components.LerNetLogo
import app.lernet.ui.icons.LerNetSymbols
import app.lernet.ui.theme.LerNetDimens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private data class RenameHooks(
    val editing: Boolean,
    val onStart: () -> Unit,
    val onCommit: (String) -> Unit,
    val onCancel: () -> Unit,
)

private data class ProfileDragHooks(
    val onStart: (Profile, Offset, Offset) -> Unit,
    val onMove: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

@Composable
fun ConfigDrawer(
    profiles: List<Profile>,
    groups: List<Group>,
    activeProfileId: String?,
    probes: Map<String, ProfileProbe>,
    actions: DrawerActions,
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var creatingGroup by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var createMenu by remember { mutableStateOf(false) }
    var movingId by remember { mutableStateOf<String?>(null) }
    var renameGroup by remember { mutableStateOf<Group?>(null) }
    var renameGroupDraft by remember { mutableStateOf("") }
    var deleteGroup by remember { mutableStateOf<Group?>(null) }
    var drag by remember { mutableStateOf<ProfileDrag?>(null) }
    var layerBounds by remember { mutableStateOf(Rect.Zero) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    val dropZones = remember { mutableStateMapOf<String, DrawerDropZone>() }
    val listState = rememberLazyListState()
    val view = LocalView.current
    val density = LocalDensity.current
    DisposableEffect(Unit) { onDispose { onDragActiveChange(false) } }
    val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key.toString() }.toSet()
    val currentProfiles by rememberUpdatedState(profiles)
    val currentEditingId by rememberUpdatedState(editingId)
    val drop = drag?.let {
        resolveDrawerDrop(
            it.pointer, dropZones.values, visibleKeys, groups, profiles.map(Profile::id), it.id,
            with(density) { 20.dp.toPx() },
        )
    }
    val dragHooks = ProfileDragHooks(
        onStart = { profile, pointer, grabOffset ->
            movingId = null
            drag = ProfileDrag(profile.id, profile.name, pointer, grabOffset)
            onDragActiveChange(true)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        },
        onMove = { delta -> drag = drag?.let { it.copy(pointer = it.pointer + delta) } },
        onEnd = {
            val source = drag
            val target = source?.let {
                resolveDrawerDrop(
                    it.pointer,
                    dropZones.values,
                    listState.layoutInfo.visibleItemsInfo.map { item -> item.key.toString() }.toSet(),
                    groups,
                    currentProfiles.map(Profile::id),
                    it.id,
                    with(density) { 20.dp.toPx() },
                )
            }
            if (source != null && target != null) {
                val sourceGroup = groups.firstOrNull { source.id in it.profileIds }
                val groupedIds = groups.flatMapTo(mutableSetOf()) { it.profileIds }
                val currentIndex = sourceGroup?.profileIds?.indexOf(source.id)
                    ?: currentProfiles.filterNot { it.id in groupedIds }.indexOfFirst { it.id == source.id }
                if (target.groupId != sourceGroup?.id || target.index != currentIndex) {
                    actions.onMoveProfile(source.id, target.groupId, target.index)
                }
            }
            drag = null
            onDragActiveChange(false)
        },
        onCancel = {
            drag = null
            onDragActiveChange(false)
        },
    )
    val currentDragHooks by rememberUpdatedState(dragHooks)
    LaunchedEffect(drag?.id) {
        while (drag != null) {
            val pointer = drag?.pointer ?: break
            val edge = with(density) { 60.dp.toPx() }
            val speed = when {
                pointer.y < listBounds.top + edge -> -((listBounds.top + edge - pointer.y) / edge).coerceIn(0f, 1f) * 22f
                pointer.y > listBounds.bottom - edge -> ((pointer.y - listBounds.bottom + edge) / edge).coerceIn(0f, 1f) * 22f
                else -> 0f
            }
            if (speed != 0f && listBounds.height > 0f) listState.scrollBy(speed)
            delay(16)
        }
    }
    LaunchedEffect(drop?.targetKey) {
        if (drag != null && drop != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val folder = groups.firstOrNull { drop?.targetKey == "group:${it.id}" }
        if (folder != null && folder.id !in expandedGroups && drag != null) {
            delay(600)
            if (drag != null) expandedGroups = expandedGroups + folder.id
        }
    }
    val visible = remember(profiles, query) {
        if (query.isBlank()) profiles else profiles.filter { it.name.contains(query, ignoreCase = true) }
    }
    if (renameGroup != null) {
        AlertDialog(
            onDismissRequest = { renameGroup = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(renameGroupDraft, { renameGroupDraft = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renameGroup?.let { actions.onRenameGroup(it.id, renameGroupDraft) }
                    renameGroup = null
                }, enabled = renameGroupDraft.isNotBlank()) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renameGroup = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteGroup = null },
            title = { Text(stringResource(R.string.group_delete_title)) },
            text = { Text(stringResource(R.string.group_delete_body, group.name)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDeleteGroup(group.id)
                    deleteGroup = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (creatingGroup) {
        AlertDialog(
            onDismissRequest = { creatingGroup = false },
            title = { Text(stringResource(R.string.drawer_new_folder)) },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onCreateGroup(groupName)
                    groupName = ""
                    creatingGroup = false
                }, enabled = groupName.isNotBlank()) { Text(stringResource(R.string.create_group)) }
            },
            dismissButton = {
                TextButton(onClick = { creatingGroup = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxHeight()) {
            Column(Modifier.padding(horizontal = LerNetDimens.screenPadding, vertical = LerNetDimens.contentPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.drawer_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.drawer_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (movingId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LerNetDimens.screenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.drawer_move_hint),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { movingId = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
            if (profiles.size > 5) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LerNetDimens.screenPadding, vertical = LerNetDimens.itemGap),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_profiles)) },
                )
            }
            HorizontalDivider()
            if (profiles.isEmpty() && groups.isEmpty()) {
                EmptyProfiles(Modifier.weight(1f))
            } else if (visible.isEmpty() && groups.none { it.name.contains(query, ignoreCase = true) }) {
                Text(
                    stringResource(R.string.search_profiles_empty),
                    modifier = Modifier
                        .weight(1f)
                        .padding(LerNetDimens.screenPadding),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val grouped = groups.flatMap { it.profileIds }.toSet()
                val ungrouped = visible.filter { it.id !in grouped }
                val allById = profiles.associateBy { it.id }
                val visibleById = visible.associateBy { it.id }
                Box(
                    Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { layerBounds = it.boundsInRoot() },
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                            .onGloballyPositioned { listBounds = it.boundsInRoot() }
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { local ->
                                        val pointer = listBounds.topLeft + local
                                        val source = dropZones.values.firstOrNull { zone ->
                                            zone.kind in listOf(DrawerZoneKind.MEMBER, DrawerZoneKind.PROFILE) &&
                                                zone.bounds.contains(pointer) &&
                                                pointer.x < zone.bounds.right - with(density) { 96.dp.toPx() } &&
                                                zone.key in listState.layoutInfo.visibleItemsInfo.map { it.key.toString() }
                                        }
                                        val profile = currentProfiles.firstOrNull { it.id == source?.profileId }
                                        if (source != null && profile != null && profile.id != currentEditingId) {
                                            currentDragHooks.onStart(profile, pointer, pointer - source.bounds.topLeft)
                                        }
                                    },
                                    onDrag = { change, delta ->
                                        if (drag != null) {
                                            change.consume()
                                            currentDragHooks.onMove(delta)
                                        }
                                    },
                                    onDragEnd = { currentDragHooks.onEnd() },
                                    onDragCancel = { currentDragHooks.onCancel() },
                                )
                            },
                    ) {
                    groups.forEachIndexed { folderIndex, group ->
                        val nameMatches = group.name.contains(query, ignoreCase = true)
                        val byId = if (nameMatches) allById else visibleById
                        val members = group.profileIds.mapNotNull(byId::get)
                        if (query.isBlank() || members.isNotEmpty() || nameMatches) {
                            item(key = "group:${group.id}") {
                                FolderHeader(
                                    group = group,
                                    expanded = group.id in expandedGroups || query.isNotBlank(),
                                    moving = movingId != null,
                                    dropHighlighted = drop?.targetKey == "group:${group.id}",
                                    onBounds = { bounds ->
                                        dropZones["group:${group.id}"] = DrawerDropZone(
                                            "group:${group.id}", DrawerZoneKind.FOLDER, bounds, group.id,
                                        )
                                    },
                                    onToggle = {
                                        val moving = movingId
                                        if (moving != null) {
                                            actions.onMoveProfile(moving, group.id, group.profileIds.size)
                                            movingId = null
                                        } else {
                                            expandedGroups = if (group.id in expandedGroups) {
                                                expandedGroups - group.id
                                            } else {
                                                expandedGroups + group.id
                                            }
                                        }
                                    },
                                    probe = probes,
                                    onRename = {
                                        renameGroup = group
                                        renameGroupDraft = group.name
                                    },
                                    onDelete = { deleteGroup = group },
                                    index = folderIndex,
                                    folderCount = groups.size,
                                    actions = actions,
                                )
                            }
                            if (group.id in expandedGroups || query.isNotBlank()) {
                                items(members, key = { "member:${group.id}:${it.id}" }) { profile ->
                                    val key = "member:${group.id}:${profile.id}"
                                    Box(Modifier.animateItem()) {
                                        ProfileDrawerItem(
                                            profile, profile.id == activeProfileId, actions,
                                            RenameHooks(
                                                editing = profile.id == editingId,
                                                onStart = { editingId = profile.id },
                                                onCommit = { name ->
                                                    actions.onRename(profile.id, name)
                                                    editingId = null
                                                },
                                                onCancel = { editingId = null },
                                            ),
                                            probe = probes[profile.id],
                                            moving = movingId == profile.id,
                                            dragging = drag?.id == profile.id,
                                            onBounds = { bounds ->
                                                dropZones[key] = DrawerDropZone(
                                                    key, DrawerZoneKind.MEMBER, bounds, group.id, profile.id,
                                                )
                                            },
                                            onMoveRequested = { movingId = profile.id },
                                            onClick = {
                                                val moving = movingId
                                                if (moving == null) {
                                                    actions.onSelect(profile.id)
                                                } else {
                                                    val targetIndex = group.profileIds.indexOf(profile.id)
                                                    val sourceIndex = group.profileIds.indexOf(moving)
                                                    val adjusted = targetIndex - if (sourceIndex in 0 until targetIndex) 1 else 0
                                                    actions.onMoveProfile(moving, group.id, adjusted)
                                                    movingId = null
                                                }
                                            },
                                        )
                                        DropGuide(drop?.targetKey == key, drop?.after == true)
                                    }
                                }
                            }
                        }
                    }
                    if (ungrouped.isNotEmpty() || movingId != null || drag != null) {
                        item(key = "ungrouped") {
                            val highlighted = drop?.targetKey == "ungrouped"
                            val headerColor by animateColorAsState(
                                if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                label = "ungrouped-drop",
                            )
                            Text(
                                stringResource(R.string.drawer_ungrouped),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (highlighted) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(headerColor)
                                    .onGloballyPositioned { bounds ->
                                        dropZones["ungrouped"] = DrawerDropZone(
                                            "ungrouped", DrawerZoneKind.UNGROUPED, bounds.boundsInRoot(),
                                        )
                                    }
                                    .clickable {
                                        movingId?.let {
                                            actions.onMoveProfile(it, null, 0)
                                            movingId = null
                                        }
                                    }
                                    .padding(LerNetDimens.screenPadding),
                            )
                        }
                    }
                    items(ungrouped, key = { "profile:${it.id}" }) { profile ->
                        val key = "profile:${profile.id}"
                        Box(Modifier.animateItem()) {
                            ProfileDrawerItem(
                                profile = profile,
                                selected = profile.id == activeProfileId,
                                actions = actions,
                                rename = RenameHooks(
                                    editing = profile.id == editingId,
                                    onStart = { editingId = profile.id },
                                    onCommit = { name ->
                                        actions.onRename(profile.id, name)
                                        editingId = null
                                    },
                                    onCancel = { editingId = null },
                                ),
                                probe = probes[profile.id],
                                moving = movingId == profile.id,
                                dragging = drag?.id == profile.id,
                                onBounds = { bounds ->
                                    dropZones[key] = DrawerDropZone(key, DrawerZoneKind.PROFILE, bounds, profileId = profile.id)
                                },
                                onMoveRequested = { movingId = profile.id },
                                onClick = {
                                    val moving = movingId
                                    if (moving == null) {
                                        actions.onSelect(profile.id)
                                    } else {
                                        if (moving in ungrouped.map { it.id }) {
                                            actions.onReorderUngrouped(moving, profile.id)
                                        } else {
                                            actions.onMoveProfile(moving, null, 0)
                                        }
                                        movingId = null
                                    }
                                },
                            )
                            DropGuide(drop?.targetKey == key, drop?.after == true)
                        }
                    }
                    }
                    drag?.let { activeDrag ->
                        val ghostWidth = with(density) { layerBounds.width.toDp() }
                            .coerceAtMost(250.dp).coerceAtLeast(120.dp)
                        val freeX = activeDrag.pointer.x - layerBounds.left - activeDrag.grabOffset.x
                        val snapX = drop?.let { target ->
                            dropZones[target.targetKey]?.bounds?.left?.minus(layerBounds.left)?.plus(with(density) { 8.dp.toPx() })
                        } ?: freeX
                        val maxX = (layerBounds.width - with(density) { ghostWidth.toPx() }).coerceAtLeast(0f)
                        val ghostX by animateFloatAsState(snapX.coerceIn(0f, maxX), label = "profile-drag-snap")
                        val ghostY = activeDrag.pointer.y - layerBounds.top - activeDrag.grabOffset.y
                        Surface(
                            modifier = Modifier
                                .offset { IntOffset(ghostX.roundToInt(), ghostY.roundToInt()) }
                                .width(ghostWidth)
                                .zIndex(2f),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(14.dp),
                            shadowElevation = 12.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(LerNetSymbols.drag(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column {
                                    Text(activeDrag.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        when {
                                            drop == null -> stringResource(R.string.drawer_drag_target_hint)
                                            drop.groupId == null -> stringResource(R.string.drawer_ungrouped)
                                            else -> groups.firstOrNull { it.id == drop.groupId }?.name.orEmpty()
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(LerNetDimens.screenPadding),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = { actions.onProbe(profiles.map { it.id }) }, enabled = profiles.isNotEmpty()) {
                    Icon(
                        LerNetSymbols.probe(), contentDescription = "Проверить все профили",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Box {
                    IconButton(onClick = { createMenu = true }) {
                        Icon(LerNetSymbols.add(), contentDescription = stringResource(R.string.drawer_create))
                    }
                    DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.drawer_new_folder)) }, onClick = {
                            createMenu = false
                            creatingGroup = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.drawer_new_profile)) }, onClick = {
                            createMenu = false
                            actions.onImport()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.transfer_import)) }, onClick = {
                            createMenu = false
                            actions.onImportArchive()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.transfer_export_all)) }, onClick = {
                            createMenu = false
                            actions.onExportAll()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderHeader(
    group: Group,
    expanded: Boolean,
    moving: Boolean,
    dropHighlighted: Boolean,
    onBounds: (Rect) -> Unit,
    onToggle: () -> Unit,
    probe: Map<String, ProfileProbe>,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    index: Int,
    folderCount: Int,
    actions: DrawerActions,
) {
    var menu by remember { mutableStateOf(false) }
    val containerColor by animateColorAsState(
        if (moving || dropHighlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        label = "folder-drop",
    )
    ListItem(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { onBounds(it.boundsInRoot()) }.clickable(onClick = onToggle),
        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (group.autoFailover) {
                    Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                        Icon(LerNetSymbols.autoSwap(), contentDescription = "Автопереключение включено",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(2.dp).size(15.dp))
                    }
                }
            }
        },
        supportingContent = {
            val results = group.profileIds.mapNotNull(probe::get)
            Text(
                if (results.any { it.running }) {
                    stringResource(R.string.drawer_folder_checking, results.count { it.running }, group.profileIds.size)
                } else if (results.isEmpty()) {
                    stringResource(R.string.drawer_folder_count, group.profileIds.size)
                } else {
                    stringResource(
                        R.string.drawer_folder_probe,
                        results.count { it.reachable }, group.profileIds.size
                    )
                }
            )
        },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.onProbe(group.profileIds) }) {
                    Icon(
                        LerNetSymbols.probe(), contentDescription = "Проверить папку",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    if (expanded) LerNetSymbols.expandMore() else LerNetSymbols.chevronRight(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(LerNetSymbols.more(), contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.drawer_move_up)) },
                            enabled = index > 0, onClick = {
                                menu = false
                                actions.onMoveGroup(group.id, index - 1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.drawer_move_down)) },
                            enabled = index < folderCount - 1,
                            onClick = {
                                menu = false
                                actions.onMoveGroup(group.id, index + 1)
                            }
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
                            menu = false
                            onRename()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                            menu = false
                            onDelete()
                        })
                        DropdownMenuItem(text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    LerNetSymbols.autoSwap(), contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(stringResource(R.string.settings_failover), maxLines = 1)
                            }
                        }, trailingIcon = {
                            Switch(checked = group.autoFailover, onCheckedChange = null)
                        }, onClick = {
                            menu = false
                            actions.onSetAutoFailover(group.id, !group.autoFailover)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.drawer_add_to_folder)) }, onClick = {
                            menu = false
                            actions.onImportIntoGroup(group.id)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.group_routes)) }, onClick = {
                            menu = false
                            actions.onGroupRoutes(group.id)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.transfer_export_group)) }, onClick = {
                            menu = false
                            actions.onExportGroup(group.id)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.drawer_manage_folders)) }, onClick = {
                            menu = false
                            actions.onGroups()
                        })
                    }
                }
            }
        },
    )
}

@Composable
private fun BoxScope.DropGuide(visible: Boolean, after: Boolean) {
    val color by animateColorAsState(
        if (visible) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "profile-drop-guide",
    )
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier
                .align(if (after) Alignment.BottomCenter else Alignment.TopCenter)
                .fillMaxWidth()
                .height(3.dp)
                .background(color),
        )
    }
}

@Composable
private fun EmptyProfiles(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LerNetLogo(size = 48.dp)
        Text(stringResource(R.string.empty_profiles), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.empty_profiles_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ProfileDrawerItem(
    profile: Profile,
    selected: Boolean,
    actions: DrawerActions,
    rename: RenameHooks,
    probe: ProfileProbe?,
    moving: Boolean,
    dragging: Boolean,
    onBounds: (Rect) -> Unit,
    onMoveRequested: () -> Unit,
    onClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .graphicsLayer { alpha = if (dragging) 0.28f else 1f },
    ) {
        if (rename.editing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = LerNetDimens.primaryActionMinHeight)
                    .padding(start = LerNetDimens.screenPadding, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActiveMark(selected)
                InlineRenameRow(current = profile.name, rename = rename)
            }
        } else {
            ProfileActionBlock(
                profile, selected, actions, rename.onStart, menu,
                probe, moving, onMoveRequested, onClick
            ) { menu = it }
        }
    }
}

@Composable
private fun ProfileActionBlock(
    profile: Profile,
    selected: Boolean,
    actions: DrawerActions,
    onRename: () -> Unit,
    menu: Boolean,
    probe: ProfileProbe?,
    moving: Boolean,
    onMoveRequested: () -> Unit,
    onClick: () -> Unit,
    onMenu: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = LerNetDimens.buttonMinHeight),
        headlineContent = {
            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                when {
                    moving -> stringResource(R.string.drawer_move_selected)
                    probe?.running == true -> stringResource(R.string.drawer_probe_running)
                    probe?.reachable == true -> "${protocolOf(profile)} · TCP ${probe.tcpMs} мс"
                    probe != null -> "${protocolOf(profile)} · ${stringResource(R.string.diag_ping_fail)}"
                    else -> protocolOf(profile)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { ActiveMark(selected) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.onProbe(listOf(profile.id)) }) {
                    if (probe?.running == true) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            LerNetSymbols.probe(), contentDescription = "Проверить профиль",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                ProfileOverflow(profile, actions, onRename, onMoveRequested, menu, onMenu)
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (moving) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
    )
}

@Composable
private fun ActiveMark(selected: Boolean) {
    if (selected) {
        Icon(
            LerNetSymbols.check(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    } else {
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun ProfileOverflow(
    profile: Profile,
    actions: DrawerActions,
    onRename: () -> Unit,
    onMoveRequested: () -> Unit,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
) {
    Box {
        IconButton(onClick = { onExpanded(true) }) {
            Icon(LerNetSymbols.more(), contentDescription = stringResource(R.string.more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_move_profile)) },
                onClick = {
                    onExpanded(false)
                    onMoveRequested()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.select_profile)) },
                onClick = {
                    onExpanded(false)
                    actions.onSelect(profile.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_profile)) },
                onClick = {
                    onExpanded(false)
                    actions.onConfig(profile.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_routes)) },
                onClick = {
                    onExpanded(false)
                    actions.onRoutes(profile.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = {
                    onExpanded(false)
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.duplicate)) },
                onClick = {
                    onExpanded(false)
                    actions.onDuplicate(profile.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    onExpanded(false)
                    actions.onDelete(profile.id)
                },
            )
        }
    }
}

@Composable
private fun RowScope.InlineRenameRow(current: String, rename: RenameHooks) {
    var draft by remember(current) { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun commit() {
        val value = draft.trim()
        if (value.isNotEmpty()) rename.onCommit(value) else rename.onCancel()
    }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 12.dp)
            .focusRequester(focus),
    )
    TextButton(onClick = { commit() }) {
        Text(stringResource(R.string.done))
    }
}

@Composable
private fun protocolOf(profile: Profile): String {
    val type = profile.selectedOutbound()?.type
    if (!type.isNullOrBlank()) return type.uppercase()
    return sourceLabel(profile.source)
}

@Composable
private fun sourceLabel(source: ProfileSource): String =
    stringResource(
        when (source) {
            ProfileSource.VLESS -> R.string.source_vless
            ProfileSource.JSON_URL -> R.string.source_json_url
            ProfileSource.JSON_PASTE -> R.string.source_json_paste
            ProfileSource.SUBSCRIPTION -> R.string.source_subscription
        },
    )
