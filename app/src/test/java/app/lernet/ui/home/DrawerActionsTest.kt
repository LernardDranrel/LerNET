package app.lernet.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerActionsTest {
    @Test fun expertProfileTapOpensEditorWithoutSelectingVpn() {
        val events = mutableListOf<String>()
        actions(events).copy(managementOnly = true).openProfile("profile-a")
        assertEquals(listOf("edit:profile-a"), events)
    }

    @Test fun vpnProfileTapRetainsSelectionBehavior() {
        val events = mutableListOf<String>()
        actions(events).openProfile("profile-a")
        assertEquals(listOf("select:profile-a"), events)
    }

    private fun actions(events: MutableList<String>) = DrawerActions(
        onSelect = { events += "select:$it" }, onConfig = { events += "edit:$it" },
        onImport = {}, onImportIntoGroup = {}, onImportArchive = {}, onExportAll = {}, onExportGroup = {},
        onCreateGroup = {}, onGroupRoutes = {}, onRoutes = {}, onDelete = {}, onDuplicate = {},
        onRename = { _, _ -> }, onGroups = {}, onProbe = {}, onMoveProfile = { _, _, _ -> },
        onRenameGroup = { _, _ -> }, onDeleteGroup = {}, onSetAutoFailover = { _, _ -> },
        onMoveGroup = { _, _ -> }, onReorderUngrouped = { _, _ -> },
    )
}
