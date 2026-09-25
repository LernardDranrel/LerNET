import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension>("detekt") {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom(
            files(
                "src/main/java",
                "src/main/kotlin",
                "src/test/java",
                "src/test/kotlin",
            ),
        )
    }

    extensions.configure<SpotlessExtension>("spotless") {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion)
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
    }
}

tasks.register("lint") {
    group = "verification"
    description = "Run Android Lint on every Android module"
    dependsOn(
        ":app:lintDebug",
        ":core-ui:lintDebug",
        ":core-config:lintDebug",
        ":core-engine:lintDebug",
    )
}

tasks.register("quality") {
    group = "verification"
    description = "lint + detekt + unit tests + assembleDebug"
    dependsOn(
        "lint",
        ":app:detekt",
        ":core-ui:detekt",
        ":core-config:detekt",
        ":core-routing:detekt",
        ":core-engine:detekt",
        ":core-config:testDebugUnitTest",
        ":core-routing:test",
        ":core-engine:testDebugUnitTest",
        ":app:assembleDebug",
    )
}
