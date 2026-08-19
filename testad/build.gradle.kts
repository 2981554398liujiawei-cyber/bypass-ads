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
}
