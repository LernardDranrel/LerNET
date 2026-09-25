package app.lernet.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.lernet.config.model.Group
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDragTest {
    private val group = Group("folder", "Folder", listOf("a", "b", "c"))

    @Test
    fun droppingAfterMemberAdjustsForSourceRemoval() {
        val zone = DrawerDropZone("member:c", DrawerZoneKind.MEMBER, Rect(0f, 100f, 300f, 160f), "folder", "c")
        val result = resolveDrawerDrop(
            Offset(100f, 150f), listOf(zone), setOf(zone.key), listOf(group), group.profileIds,
            draggedId = "a", snapDistancePx = 20f,
        )
        assertEquals(DrawerDrop("member:c", "folder", 2, after = true), result)
    }

    @Test
    fun folderHeaderAcceptsProfileAtEnd() {
        val zone = DrawerDropZone("group:folder", DrawerZoneKind.FOLDER, Rect(0f, 0f, 300f, 70f), "folder")
        val result = resolveDrawerDrop(
            Offset(100f, 30f), listOf(zone), setOf(zone.key), listOf(group), listOf("outside") + group.profileIds,
            draggedId = "outside", snapDistancePx = 20f,
        )
        assertEquals(DrawerDrop("group:folder", "folder", 3), result)
    }

    @Test
    fun invisibleRowCannotReceiveDrop() {
        val zone = DrawerDropZone("group:folder", DrawerZoneKind.FOLDER, Rect(0f, 0f, 300f, 70f), "folder")
        val result = resolveDrawerDrop(
            Offset(100f, 30f), listOf(zone), emptySet(), listOf(group), group.profileIds,
            draggedId = "a", snapDistancePx = 20f,
        )
        assertEquals(null, result)
    }

    @Test
    fun ungroupedReorderKeepsGroupedPositions() {
        assertEquals(
            listOf("g", "b", "a", "c"),
            orderAfterUngroupedMove(listOf("g", "a", "b", "c"), setOf("g"), "b", 0),
        )
    }

    @Test
    fun movingOutOfFolderInsertsAmongUngrouped() {
        assertEquals(
            listOf("a", "g", "b"),
            orderAfterUngroupedMove(listOf("a", "g", "b"), emptySet(), "g", 1),
        )
    }
}
