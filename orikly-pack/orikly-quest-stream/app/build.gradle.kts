plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Всё, что зависит от релиза, приходит из переменных окружения (GitHub Actions).
// Локально без них собирается debug-подписанный APK с ключом "orikly".
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

// SHA-256 в виде hex — так же считает AppLock.sha256 в приложении.
fun sha256(s: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

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
        // Адрес сервера на ноутбуке через интернет, например wss://stream.example.org/push. Пусто — только локальная сеть.
        val relayUrl = env("RELAY_URL") ?: ""
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        // Пароль блокировки магазина / браузера (AppLock.kt) из секрета APP_LOCK_PASSWORD: в APK — только хэш.
        // Соль — как AppLock.SALT. Нет секрета — блокировка выключена.
        val lockHash = env("APP_LOCK_PASSWORD")?.let { sha256("org.orikly.queststream:$it") } ?: ""
        buildConfigField("String", "LOCK_PASSWORD_SHA256", "\"$lockHash\"")
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

dependencies {
    // WebSocket-клиент для отправки картинки на сервер ноутбука через интернет.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
