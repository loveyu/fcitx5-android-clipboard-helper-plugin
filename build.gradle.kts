import java.util.Properties

plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.2.0"
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.clipboard"
    compileSdk = 35

    buildFeatures {
        viewBinding = true
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
            val props = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

            storeFile = (System.getenv("SIGNING_STORE_FILE") ?: props.getProperty("signing.storeFile"))?.let { file(it) }
            storeType = "PKCS12"
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: props.getProperty("signing.storePassword") ?: ""
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: props.getProperty("signing.keyAlias") ?: "fcitx5-android-clipboard-plugin"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: props.getProperty("signing.keyPassword") ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
