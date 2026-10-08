package app.lernet.ui.home

data class DrawerActions(
    val onSelect: (String) -> Unit,
    val onImport: () -> Unit,
    val onImportIntoGroup: (String) -> Unit,
    val onImportArchive: () -> Unit,
    val onExportAll: () -> Unit,
    val onExportGroup: (String) -> Unit,
    val onCreateGroup: (String) -> Unit,
    val onGroupRoutes: (String) -> Unit,
    val onRoutes: (String) -> Unit,
    val onConfig: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onDuplicate: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onGroups: () -> Unit,
    val onProbe: (List<String>) -> Unit,
    val onMoveProfile: (String, String?, Int) -> Unit,
    val onRenameGroup: (String, String) -> Unit,
    val onDeleteGroup: (String) -> Unit,
    val onSetAutoFailover: (String, Boolean) -> Unit,
    val onMoveGroup: (String, Int) -> Unit,
    val onReorderUngrouped: (String, String) -> Unit,
    val managementOnly: Boolean = false,
) {
    fun openProfile(id: String) {
        if (managementOnly) onConfig(id) else onSelect(id)
    }
}
