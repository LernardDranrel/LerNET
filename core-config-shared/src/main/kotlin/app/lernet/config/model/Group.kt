package app.lernet.config.model

data class Group(
    val id: String,
    val name: String,
    val profileIds: List<String>,
    val canvasLayout: String? = null,
    val hasRoutes: Boolean = false,
    val autoFailover: Boolean = false,
    /** Set when this folder is a live subscription. The remote list owns [profileIds]. */
    val subscriptionUrl: String? = null,
)
