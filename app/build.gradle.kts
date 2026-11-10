plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// API keys are NOT built into the APK: they are entered on the phone (Settings → Routing) and stored in
// DataStore only. The app works without any key (straight-line routes).

// Build identity shown in Settings, so the installed APK can be matched to a CI run.
val gitSha: String = (System.getenv("GITHUB_SHA") ?: "local").take(7)
val ciRun: String = System.getenv("GITHUB_RUN_NUMBER") ?: "-"

android {
    namespace = "com.mockroute.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mockroute.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.7.0"

        // Target phone (POCO F7 Ultra) is arm64; skipping other ABIs cuts the APK from ~75 MB to ~25 MB.
        ndk { abiFilters += listOf("arm64-v8a") }

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "CI_RUN", "\"$ciRun\"")
    }

    signingConfigs {
        // Committed, debug-only keystore so every CI build has the same signature and
        // new APKs install over old ones. NOT a secret; never use it for release builds.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.location)
    // MapLibre Compose 0.14 bundles the MapLibre Native Android (OpenGL) runtime: no extra render artifact needed.
    implementation(libs.maplibre.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
