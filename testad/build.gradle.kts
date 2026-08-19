plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.bypassads.testad"
    compileSdk = rootProject.ext["android.compileSdk"] as Int

    defaultConfig {
        applicationId = "app.bypassads.testad"
        minSdk = rootProject.ext["android.minSdk"] as Int
        targetSdk = rootProject.ext["android.targetSdk"] as Int
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ""
        }
        create("exact") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".exact"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
