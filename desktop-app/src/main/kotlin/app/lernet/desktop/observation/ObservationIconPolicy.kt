package app.lernet.desktop.observation

/** Do not turn a passive inventory into SMB requests by loading an icon from a network path. */
internal object ObservationIconPolicy {
    fun allows(path: String, isFixedDrive: (String) -> Boolean, isPlainLocalEntry: (String) -> Boolean): Boolean {
        if (!path.matches(Regex("^[a-zA-Z]:[\\\\/].+"))) return false
        val normalized = path.replace('/', '\\')
        val root = normalized.take(3)
        val segments = normalized.drop(3).split('\\')
        if (segments.any { it.isBlank() || it in setOf(".", "..") || ':' in it }) return false
        if (!isFixedDrive(root)) return false
        var current = root.trimEnd('\\')
        for (segment in segments) {
            current += "\\" + segment
            if (!isPlainLocalEntry(current)) return false
        }
        return true
    }
}
