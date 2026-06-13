plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.androidclaw.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.androidclaw.app"
        minSdk = 29
        targetSdk = 35
        // versionCode comes from CI (the GitHub run number) so each rolling build
        // outranks the last; falls back to 1 for local builds.
        versionCode = (project.findProperty("clawVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "0.1." + ((project.findProperty("clawVersionCode") as String?) ?: "0")
    }

    signingConfigs {
        // A FIXED debug keystore committed to the repo. Without this, every CI
        // runner generates its own random debug.keystore, so each build is signed
        // with a different key and Android refuses to update — forcing an uninstall.
        // With one stable key, every rolling build installs straight over the last.
        getByName("debug") {
            storeFile = rootProject.file("claw-debug.keystore")
            storePassword = "clawclaw"
            keyAlias = "clawkey"
            keyPassword = "clawclaw"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-gateway"))
    implementation(project(":core-control"))
    implementation(project(":core-overlay"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
