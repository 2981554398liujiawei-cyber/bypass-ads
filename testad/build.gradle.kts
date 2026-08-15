plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.bypassads.testad"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.bypassads.testad"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
