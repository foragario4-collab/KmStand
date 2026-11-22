plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Kommt beim Bauen auf GitHub automatisch mit (Nummer des Builds und Name des Repositorys).
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0
val ghRepo = (project.findProperty("ghRepo") as String?) ?: ""

android {
    namespace = "at.kmstand.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "at.kmstand.app"
        minSdk = 26
        targetSdk = 34
        // 100 + Build-Nummer: immer höher als alle bisherigen Versionen (1–6)
        versionCode = 100 + buildNumber
        versionName = "1.7.$buildNumber"
        buildConfigField("String", "GH_REPO", "\"$ghRepo\"")
    }

    buildFeatures { buildConfig = true }

    // Fester Schlüssel: Updates lassen sich immer über die alte Version installieren,
    // gespeicherte Kilometer bleiben erhalten.
    signingConfigs {
        create("kmstand") {
            storeFile = file("kmstand.keystore")
            storePassword = "kmstand2026"
            keyAlias = "kmstand"
            keyPassword = "kmstand2026"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("kmstand") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kmstand")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
