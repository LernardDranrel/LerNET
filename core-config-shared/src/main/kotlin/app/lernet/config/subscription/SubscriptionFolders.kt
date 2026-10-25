package app.lernet.config.subscription

import java.net.URI

/** A folder the app already has, as the adoption planner sees it. */
data class FolderView(
    val id: String,
    val name: String,
    val profileIds: List<String>,
    val subscriptionUrl: String? = null,
)

/**
 * Where the configs of one subscription belong.
 * [folderId] is null when a new folder must be created; [memberIds] is the exact membership, in order.
 */
data class FolderAdoption(
    val folderId: String?,
    val name: String,
    val create: Boolean,
    val memberIds: List<String>,
)

/**
 * A subscription is a folder backed by a remote list. Configs that carry the address are owned by that
 * source; a copy the user takes has no address and lives in the general config space.
 */
object SubscriptionFolders {
    fun owned(subscriptionUrl: String?): Boolean = !subscriptionUrl.isNullOrBlank()

    /** Visible folder name. Only the host is used: the rest of the address is an access secret. */
    fun folderName(url: String): String {
        val host = runCatching { URI(url.trim()).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        return if (host == null) "Подписка" else "Подписка · $host"
    }

    /**
     * Pick the folder that should hold [subscribedIds].
     * A folder already marked with [url] wins. Otherwise a single ordinary folder whose members are
     * all from this subscription (a previous one-shot import) is adopted. Otherwise a new folder is created.
     */
    fun adopt(url: String, subscribedIds: List<String>, folders: List<FolderView>): FolderAdoption {
        val wanted = subscribedIds.distinct()
        val wantedSet = wanted.toSet()
        val marked = folders.firstOrNull { it.subscriptionUrl == url }
        if (marked != null) {
            return FolderAdoption(marked.id, marked.name, false, ordered(marked.profileIds, wanted, wantedSet))
        }
        val exclusive = folders.filter { folder ->
            !owned(folder.subscriptionUrl) &&
                folder.profileIds.isNotEmpty() &&
                folder.profileIds.all { it in wantedSet }
        }
        val host = exclusive.singleOrNull()
        if (host != null) {
            return FolderAdoption(host.id, host.name, false, ordered(host.profileIds, wanted, wantedSet))
        }
        return FolderAdoption(null, folderName(url), true, wanted)
    }

    private fun ordered(current: List<String>, wanted: List<String>, wantedSet: Set<String>): List<String> {
        val kept = current.filter { it in wantedSet }.distinct()
        val keptSet = kept.toSet()
        return kept + wanted.filter { it !in keptSet }
    }
}
