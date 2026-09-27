import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val packEmuAbis =
    providers.gradleProperty("emuAbis").orElse("false").get().toBoolean()

val signingPropertiesFile = rootProject.file(".release/signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use(::load)
}

android {
    namespace = "app.lernet"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lernet"
        minSdk = 26
        targetSdk = 36
        versionCode = 102
        versionName = "1.0.2"
        buildConfigField("String", "GIT_SHA", "\"${gitHeadSha()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Phone ship stays arm64-v8a. Leadaxe AAR also ships real x86_64/x86
            // libbox.so; `-PemuAbis` packs those for the box x86_64 AVD only.
            abiFilters +=
                if (packEmuAbis) {
                    listOf("x86_64", "x86")
                } else {
                    listOf("arm64-v8a")
                }
        }
    }

    signingConfigs {
        if (signingPropertiesFile.isFile) create("lernetRelease") {
            storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            if (signingPropertiesFile.isFile) signingConfig = signingConfigs.getByName("lernetRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += setOf("MissingTranslation", "GradleDependency")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

fun gitHeadSha(): String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short=12", "HEAD")
        .directory(rootProject.projectDir)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val text = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && text.matches(Regex("[0-9a-fA-F]{7,40}"))) text else "unknown"
}.getOrDefault("unknown")

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-config"))
    implementation(project(":core-routing"))
    implementation(project(":core-engine"))
    implementation(files("../core-engine/libs/libbox.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.compose.infinite.canvas)
    implementation(libs.reorderable)
    implementation(libs.material.symbols.outlined)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
