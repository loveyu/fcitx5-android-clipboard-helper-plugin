import java.util.Properties

plugins {
    id("com.android.application") version "9.2.0"
    id("org.jetbrains.kotlin.android") version "2.2.10"
}

// Load signing properties once at the top level so both signingConfigs and buildTypes can use them.
val signingProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val hasReleaseKey = System.getenv("SIGNING_STORE_FILE") != null ||
        signingProps.getProperty("signing.storeFile") != null

android {
    namespace = "org.fcitx.fcitx5.android.plugin.clipboard"
    compileSdk = 35

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.clipboard"
        minSdk = 24
        targetSdk = 35
        val versionProps = Properties()
        listOf("version.properties", "version.local.properties").forEach { name ->
            val f = rootProject.file(name)
            if (f.exists()) {
                f.inputStream().use { versionProps.load(it) }
            }
        }
        val envVersionName = System.getenv("PLUGIN_VERSION")
        val envVersionCode = System.getenv("PLUGIN_VERSION_CODE")
        val fileVersionName = versionProps.getProperty("versionName")
        val fileVersionCode = versionProps.getProperty("versionCode")
        val fallbackVersionName = "0.1.0"
        val fallbackVersionCode = 1000000

        versionName = envVersionName ?: fileVersionName ?: fallbackVersionName
        versionCode = envVersionCode?.toIntOrNull()
            ?: fileVersionCode?.toIntOrNull()
            ?: fallbackVersionCode
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    signingConfigs {
        create("release") {
            storeFile = (System.getenv("SIGNING_STORE_FILE") ?: signingProps.getProperty("signing.storeFile"))?.let { file(it) }
            storeType = "PKCS12"
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: signingProps.getProperty("signing.storePassword") ?: ""
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: signingProps.getProperty("signing.keyAlias") ?: "fcitx5-android-clipboard-plugin"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: signingProps.getProperty("signing.keyPassword") ?: storePassword
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["fcitxAppId"] = "org.fcitx.fcitx5.android.debug"
            buildConfigField("String", "FCITX_APP_ID", "\"org.fcitx.fcitx5.android.debug\"")
        }
        release {
            manifestPlaceholders["fcitxAppId"] = "org.fcitx.fcitx5.android"
            buildConfigField("String", "FCITX_APP_ID", "\"org.fcitx.fcitx5.android\"")
            isMinifyEnabled = false
            // Use the release keystore when configured; otherwise fall back to the debug keystore
            // so a locally-built release APK can still be tested alongside a debug fcitx5-android
            // (both would then share the standard Android debug certificate).
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
