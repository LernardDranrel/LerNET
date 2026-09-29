package app.lernet.ui.nav

sealed class Dest(val route: String) {
    data object Home : Dest("home")

    data object Import : Dest("import")

    data object Settings : Dest("settings")

    data object Diag : Dest("diag")

    data object Network : Dest("network")

    data object Groups : Dest("groups")

    data object Routes : Dest("routes/{profileId}") {
        fun of(profileId: String): String = "routes/$profileId"
    }

    data object Config : Dest("config/{profileId}") {
        fun of(profileId: String): String = "config/$profileId"
    }
}
