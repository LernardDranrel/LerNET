pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LerNET"

include(
    ":app",
    ":core-ui",
    ":core-config",
    ":core-config-shared",
    ":core-routing",
    ":core-engine",
    ":core-engine-shared",
    ":desktop-app",
)
