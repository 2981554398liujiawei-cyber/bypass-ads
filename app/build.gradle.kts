import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val releasePropertiesPath = System.getenv("BYPASS_ADS_SIGNING_PROPERTIES")
    ?.takeIf { it.isNotBlank() }
    ?.let(::file)
val releaseProperties = Properties().apply {
    if (releasePropertiesPath?.isFile == true) {
        releasePropertiesPath.inputStream().use(::load)
    }
}
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseStoreFile = releaseProperties.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }
    ?.let(rootProject::file)
    ?.canonicalFile
val releaseSigningReady = releaseSigningKeys.all { key -> !releaseProperties.getProperty(key).isNullOrBlank() } &&
    releaseStoreFile?.isFile == true &&
    !releaseStoreFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())
val releaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName == "assembleRelease" ||
        taskName == "bundleRelease" ||
        taskName == ":app:assembleRelease" ||
        taskName == ":app:bundleRelease"
}

if (releaseTaskRequested) {
    check(releaseSigningReady) {
        "Release signing is required. Set BYPASS_ADS_SIGNING_PROPERTIES to an external properties file described in docs/release-candidate.md."
    }
}

android {
    namespace = "app.bypassads"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.bypassads"
        minSdk = 35
        targetSdk = 37
        versionCode = 1_000_001
        versionName = "1.0.0-rc1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ACTIVE_EXPERIMENTAL", "false")
        }
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = requireNotNull(releaseStoreFile)
                    storePassword = requireNotNull(releaseProperties.getProperty("storePassword"))
                    keyAlias = requireNotNull(releaseProperties.getProperty("keyAlias"))
                    keyPassword = requireNotNull(releaseProperties.getProperty("keyPassword"))
                }
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "ACTIVE_EXPERIMENTAL", "false")
        }
        create("experimental") {
            // M2.2: isolated experimental build. Separate applicationId, never
            // minified, debug-signed so it can be installed side by side with
            // RC. ACTIVE_EXPERIMENTAL gates the real actuation capability;
            // release/debug builds always compile with it false.
            applicationIdSuffix = ".experimental"
            versionNameSuffix = "-experimental"
            buildConfigField("boolean", "ACTIVE_EXPERIMENTAL", "true")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
