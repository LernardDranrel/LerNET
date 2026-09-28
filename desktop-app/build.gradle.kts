plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.compose") version "1.10.3"
}

val lernetVersion = providers.gradleProperty("lernetVersion").get()

tasks.processResources {
    inputs.property("lernetVersion", lernetVersion)
    filesMatching("lernet-version.txt") {
        expand("lernetVersion" to lernetVersion)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

sourceSets.main {
    resources.srcDir("../core-engine/src/main/assets")
}

dependencies {
    implementation(project(":core-config-shared"))
    implementation(project(":core-engine-shared"))
    implementation(project(":core-routing"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("net.java.dev.jna:jna:5.6.0")
    implementation("net.java.dev.jna:jna-platform:5.6.0")
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

compose.desktop {
    application {
        mainClass = "app.lernet.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            packageName = "LerNET"
            packageVersion = lernetVersion
            // Desktop features use JDK services that jdeps can miss through reflection.
            includeAllModules = true
            windows {
                console = providers.gradleProperty("lernet.debugLauncher").orNull == "true"
                iconFile.set(project.file("src/main/resources/lernet.ico"))
            }
        }
    }
}
