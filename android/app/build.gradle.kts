// Release signing comes from the environment (set by the release workflow from repo secrets):
// SAYLO_KEYSTORE = path to the .jks, SAYLO_KEYSTORE_PASSWORD = store and key password (alias "saylo").
// Without them the release APK is signed with the debug key.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystore: String? = System.getenv("SAYLO_KEYSTORE")

android {
    namespace = "ai.mitovoid.saylo"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.mitovoid.saylo"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("SAYLO_BUILD")?.toInt() ?: 1
        // Release builds get "0.1.<run>" from the workflow; local builds are "dev" and never offer updates.
        versionName = System.getenv("SAYLO_VERSION") ?: "dev"
    }

    // The cleanup prompts are shared with the Windows app: the APK reads them straight from ../../prompts.
    sourceSets["main"].assets.srcDir("../../prompts")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("SAYLO_KEYSTORE_PASSWORD")
                keyAlias = "saylo"
                keyPassword = System.getenv("SAYLO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 strips the unused parts of Compose: the APK is about a third the size.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
}
