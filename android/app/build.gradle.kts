// Release signing comes from the environment (set by the release workflow from repo secrets):
// SAYLO_KEYSTORE = path to the .jks, SAYLO_KEYSTORE_PASSWORD = store and key password (alias "saylo").
// Without them the release APK is signed with the debug key.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionName = System.getenv("SAYLO_VERSION") ?: "0.1.0"
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
            isMinifyEnabled = false
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
