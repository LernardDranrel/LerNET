package app.lernet.ui.diag

/**
 * Which app owns a connection. The only inputs are the Android UID's packages
 * and processInfo package names from the VPN stack. A hostname is not an owner.
 *
 * [android.content.pm.PackageManager.getPackagesForUid] is empty for other apps
 * unless the manifest declares QUERY_ALL_PACKAGES. That permission is install-time
 * on a sideloaded debug build. If the platform still hides the package, the card
 * stays `uid N` — a number is not replaced with a guessed name.
 */
internal sealed class AppFace {
    data class Installed(val label: String, val packageName: String) : AppFace()

    data object System : AppFace()

    data class Uid(val uid: Int) : AppFace()

    data class Pid(val pid: Long) : AppFace()
}

internal object AppIdentity {
    private const val APP_UID_START = 10_000

    fun resolve(
        uid: Int?,
        pid: Long?,
        packagesForUid: List<String>,
        processPackages: List<String> = emptyList(),
        hasLauncher: (String) -> Boolean,
        labelForPackage: (String) -> String?,
    ): AppFace {
        val owned = (packagesForUid + processPackages.flatMap(::packageTokens)).distinct()
        val labeled = labeledPackages(owned, labelForPackage)
        if (labeled.isEmpty()) return unresolved(uid, pid)
        if (uid != null && uid < APP_UID_START && labeled.size > 1) return AppFace.System
        val chosen = labeled.firstOrNull { hasLauncher(it.first) } ?: labeled.first()
        return AppFace.Installed(chosen.second, chosen.first)
    }

    /** Process paths and raw package names from libbox — never hostnames. */
    fun packageTokens(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "?" || trimmed == "-") return emptyList()
        if (looksLikePackage(trimmed)) return listOf(trimmed)
        val match = Regex("""([a-zA-Z]\w*(?:\.[a-zA-Z]\w*)+)""").findAll(trimmed)
        return match.map { it.groupValues[1] }.filter(::looksLikePackage).toList()
    }

    private val publicTlds = setOf(
        "com", "net", "org", "edu", "gov", "io", "app", "dev", "ru", "uk", "de", "fr", "info", "biz",
    )

    private fun looksLikePackage(value: String): Boolean {
        if (!value.contains('.')) return false
        if (value.contains('/') || value.contains(' ') || value.contains(':')) return false
        if (!value.all { it.isLetterOrDigit() || it == '_' || it == '.' }) return false
        val last = value.substringAfterLast('.').lowercase()
        if (last in publicTlds) return false
        return value.count { it == '.' } >= 1
    }

    private fun labeledPackages(
        packages: List<String>,
        labelForPackage: (String) -> String?,
    ): List<Pair<String, String>> = packages.distinct().mapNotNull { pkg ->
        val label = labelForPackage(pkg) ?: return@mapNotNull null
        pkg to label
    }

    private fun unresolved(uid: Int?, pid: Long?): AppFace = when {
        uid != null && uid < APP_UID_START -> AppFace.System
        uid != null -> AppFace.Uid(uid)
        pid != null && pid > 0L -> AppFace.Pid(pid)
        else -> AppFace.System
    }
}
