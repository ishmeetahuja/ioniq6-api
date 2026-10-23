plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.ishmeet.ioniq6prox"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ishmeetahuja.ioniq6"
        minSdk = 31          // Android 12+
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        // A release tag "v1.2.3" names the version; other builds are "dev-<run>".
        versionName = System.getenv("APP_VERSION") ?: "dev-${System.getenv("GITHUB_RUN_NUMBER") ?: "local"}"
    }

    // Release signing key: comes from the environment (GitHub Actions secrets), never from the repo.
    // Without it (a local build, or a fork without the secrets) the release APK is signed with the
    // standard debug key: fine for trying it out, but it won't install over an official release.
    val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
