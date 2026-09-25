package app.lernet.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.lernet.config.model.Group

internal enum class DrawerZoneKind { FOLDER, MEMBER, UNGROUPED, PROFILE }

internal data class DrawerDropZone(
    val key: String,
    val kind: DrawerZoneKind,
    val bounds: Rect,
    val groupId: String? = null,
    val profileId: String? = null,
)

internal data class DrawerDrop(
    val targetKey: String,
    val groupId: String?,
    val index: Int,
    val after: Boolean = false,
)

internal data class ProfileDrag(
    val id: String,
    val name: String,
    val pointer: Offset,
    val grabOffset: Offset,
)

/** Finds the nearest visible row and converts its upper/lower half to a stable insertion index. */
internal fun resolveDrawerDrop(
    pointer: Offset,
    zones: Collection<DrawerDropZone>,
    visibleKeys: Set<String>,
    groups: List<Group>,
    orderedProfileIds: List<String>,
    draggedId: String,
    snapDistancePx: Float,
): DrawerDrop? {
    val zone = zones.asSequence()
        .filter { it.key in visibleKeys && pointer.x in it.bounds.left..it.bounds.right }
        .map { candidate ->
            val distance = when {
                pointer.y < candidate.bounds.top -> candidate.bounds.top - pointer.y
                pointer.y > candidate.bounds.bottom -> pointer.y - candidate.bounds.bottom
                else -> 0f
            }
            candidate to distance
        }
        .filter { it.second <= snapDistancePx }
        .minByOrNull { it.second }
        ?.first ?: return null

    val sourceGroup = groups.firstOrNull { draggedId in it.profileIds }
    return when (zone.kind) {
        DrawerZoneKind.FOLDER -> {
            val group = groups.firstOrNull { it.id == zone.groupId } ?: return null
            DrawerDrop(zone.key, group.id, group.profileIds.size - if (sourceGroup?.id == group.id) 1 else 0)
        }
        DrawerZoneKind.MEMBER -> {
            val group = groups.firstOrNull { it.id == zone.groupId } ?: return null
            val rowIndex = group.profileIds.indexOf(zone.profileId)
            if (rowIndex < 0) return null
            val after = pointer.y >= zone.bounds.center.y
            val rawIndex = rowIndex + if (after) 1 else 0
            val sourceIndex = if (sourceGroup?.id == group.id) group.profileIds.indexOf(draggedId) else -1
            val index = rawIndex - if (sourceIndex >= 0 && sourceIndex < rawIndex) 1 else 0
            DrawerDrop(zone.key, group.id, index, after)
        }
        DrawerZoneKind.UNGROUPED -> DrawerDrop(zone.key, null, 0)
        DrawerZoneKind.PROFILE -> {
            val grouped = groups.flatMapTo(mutableSetOf()) { it.profileIds }
            val ungrouped = orderedProfileIds.filterNot { it in grouped }
            val rowIndex = ungrouped.indexOf(zone.profileId)
            if (rowIndex < 0) return null
            val after = pointer.y >= zone.bounds.center.y
            val rawIndex = rowIndex + if (after) 1 else 0
            val sourceIndex = ungrouped.indexOf(draggedId)
            val index = rawIndex - if (sourceIndex >= 0 && sourceIndex < rawIndex) 1 else 0
            DrawerDrop(zone.key, null, index, after)
        }
    }
}

/** Keeps the global profile order stable while inserting into the visible ungrouped order. */
internal fun orderAfterUngroupedMove(
    orderedIds: List<String>,
    groupedIds: Set<String>,
    draggedId: String,
    targetIndex: Int,
): List<String> {
    if (draggedId !in orderedIds) return orderedIds
    val remaining = orderedIds.filterNot { it == draggedId }
    val ungrouped = remaining.filterNot { it in groupedIds }
    val beforeId = ungrouped.getOrNull(targetIndex.coerceAtLeast(0))
    val insertAt = when {
        beforeId != null -> remaining.indexOf(beforeId)
        ungrouped.isNotEmpty() -> remaining.indexOf(ungrouped.last()) + 1
        else -> remaining.size
    }
    return remaining.toMutableList().apply { add(insertAt, draggedId) }
}
