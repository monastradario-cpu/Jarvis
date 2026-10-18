plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.jarvis"
        minSdk = 24
        targetSdk = 35
        // Ogni build su GitHub ha un numero più alto, così l'app si aggiorna senza disinstallarla
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

        // Solo i processori dei telefoni moderni: l'app pesa meno
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Chiave fissa: gli aggiornamenti si installano sopra la versione precedente
    // senza perdere note, ricordi e impostazioni.
    signingConfigs {
        getByName("debug") {
            storeFile = file("jarvis-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    // Parola di attivazione "Jarvis" riconosciuta offline
    implementation("com.alphacephei:vosk-android:0.3.47") {
        exclude(group = "net.java.dev.jna")
    }
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
