plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.12"
}

android {
    namespace = "com.valen.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.valen.launcher"
        minSdk = 34
        targetSdk = 37
        versionCode = 10
        versionName = "1.0.0-rc9"
    }

    flavorDimensions += "appearance"
    productFlavors {
        create("classic") { dimension = "appearance" }
        create("retro") {
            dimension = "appearance"
            applicationIdSuffix = ".retro"
            versionNameSuffix = "-retro"
        }
    }

    buildFeatures {
        compose = true
    }
}

ksp { arg("room.schemaLocation", file("schemas").path) }

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
}
