plugins {
    id("com.android.application")
}

android {
    namespace = "dev.opencodemobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.opencodemobile"
        minSdk = 26
        // targetSdk 28 is intentional: Android only allows executing binaries from app-private
        // storage for apps targeting API 28 or lower without SELinux/W^X workarounds.
        targetSdk = 28
        versionCode = 4
        versionName = "1.0.3"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    packaging {
        // Termux's libtermux.so is loaded with System.loadLibrary(), so it must be extracted
        // from the APK rather than mapped directly.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No dependencies: the terminal, UI and container plumbing are all in-tree.
