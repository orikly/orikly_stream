plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Всё, что зависит от релиза, приходит из переменных окружения (GitHub Actions).
// Локально без них собирается debug-подписанный APK с ключом "orikly".
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "org.orikly.queststream"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.orikly.queststream"
        minSdk = 29
        targetSdk = 34
        versionCode = env("VERSION_CODE")?.toInt() ?: 1
        versionName = env("VERSION_NAME") ?: "dev"

        val streamKey = env("STREAM_KEY") ?: "orikly"
        buildConfigField("String", "STREAM_KEY", "\"$streamKey\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = env("SIGNING_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = env("SIGNING_STORE_PASSWORD")
                keyAlias = env("SIGNING_KEY_ALIAS")
                keyPassword = env("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (env("SIGNING_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
