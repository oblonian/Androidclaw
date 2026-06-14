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
        // versionCode must STRICTLY increase across every build, whatever its
        // source (CI or local). If a build's code is lower than the installed one,
        // Android rejects it as a downgrade and the only way forward is an
        // uninstall — which wipes the Keystore-encrypted credentials AND the
        // accessibility-service grant, forcing the user to re-auth and re-enable
        // screen control on every "update".
        //
        // Wall-clock minutes-since-epoch gives a monotonic code that behaves the
        // same for CI and local builds: a build made later always outranks one made
        // earlier, so installs are always in-place. (~29M today; fits in an Int with
        // headroom until ~year 5900.) clawVersionCode (the CI run number) is kept
        // only as a human-readable versionName suffix.
        versionCode = (System.currentTimeMillis() / 60_000L).toInt()
        versionName = "0.1." + ((project.findProperty("clawVersionCode") as String?) ?: "dev")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
