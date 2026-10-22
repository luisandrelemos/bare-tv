plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.luisandrelemos.bare"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.luisandrelemos.bare"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 strips everything unused, Kotlin's stdlib included: a tiny APK and a fast start on a slow TV chip.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded over adb, never published: the debug key is enough for now.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Kotlin metadata the app never reads at runtime.
            excludes += setOf("kotlin/**", "META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin")
        }
    }
}

// No dependencies on purpose: only the Android SDK and Kotlin's stdlib. Keep it that way.
