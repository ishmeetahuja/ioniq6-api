import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Public identity. One file so the Android id, the iOS bundle id and the docs stay the same.
val publicConfig = Properties().apply {
    val f = File(rootDir, "../config/public.properties")
    check(f.isFile) { "Missing ${f.path}. The public app id and name live there." }
    f.inputStream().use { load(it) }
}

fun Properties.requireKey(key: String): String =
    getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("config/public.properties is missing $key")

val publicAppId = publicConfig.requireKey("applicationId")
val publicAppName = publicConfig.requireKey("appName")

// Optional local signing file (gitignored). CI passes the same values as environment variables.
val keystoreProps = Properties().apply {
    val f = File(rootDir, "keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(propName)?.trim()?.takeIf { it.isNotEmpty() }

android {
    namespace = publicAppId
    compileSdk = 35

    defaultConfig {
        applicationId = publicAppId
        minSdk = 31          // Android 12+
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        // A release tag "v1.2.3" names the version; other builds are "dev-<run>".
        versionName = System.getenv("APP_VERSION") ?: "dev-${System.getenv("GITHUB_RUN_NUMBER") ?: "local"}"
        resValue("string", "app_name", publicAppName)
    }

    buildFeatures {
        buildConfig = true
    }

    // Release signing key: environment (GitHub Actions secrets) or android/keystore.properties.
    // Without it (a local build, or a fork without the secrets) the release APK is signed with the
    // standard debug key: fine for trying it out, but it won't install over an official release.
    val keystorePath = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")
    signingConfigs {
        if (keystorePath != null) create("release") {
            storeFile = File(keystorePath)
            storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
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
