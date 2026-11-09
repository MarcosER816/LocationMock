import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional API keys: environment variable (GitHub Actions secrets) → local.properties → empty string.
// A missing key must never fail the build; the app works without keys (walk mode, straight-line routes).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun apiKey(name: String): String {
    val value = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: ""
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.mockroute.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mockroute.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"

        buildConfigField("String", "ORS_API_KEY", apiKey("ORS_API_KEY"))
        buildConfigField("String", "TOMTOM_API_KEY", apiKey("TOMTOM_API_KEY"))
        buildConfigField("String", "GRAPHHOPPER_API_KEY", apiKey("GRAPHHOPPER_API_KEY"))
        buildConfigField("String", "GOOGLE_ROUTES_API_KEY", apiKey("GOOGLE_ROUTES_API_KEY"))
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
    debugImplementation(libs.androidx.compose.ui.tooling)
}
