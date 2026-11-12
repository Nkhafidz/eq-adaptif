plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.khafidz.eqadaptif"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.khafidz.eqadaptif"
        minSdk = 28          // DynamicsProcessing butuh Android 9+
        targetSdk = 34
        versionCode = 12
        versionName = "4.0"
    }

    // Kunci tanda tangan tetap, supaya update APK bisa dipasang di atas versi lama
    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/eqadaptif.jks")
            storePassword = "eqadaptif"
            keyAlias = "eqadaptif"
            keyPassword = "eqadaptif"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
