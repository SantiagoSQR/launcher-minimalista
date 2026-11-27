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
        versionCode = 13
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            // Compose debug builds are noticeably slower; this is the build to use day to day.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with this computer's debug key so it installs over the debug build and keeps its data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }
}

ksp { arg("room.schemaLocation", file("schemas").path) }

// APKs come out as launcher-minimalista-debug.apk / launcher-minimalista-release.apk.
base { archivesName.set("launcher-minimalista") }

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
}
