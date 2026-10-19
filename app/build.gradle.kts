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
        // Collaudo automatico sul telefono simulato
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // per il collaudo sull'emulatore si costruisce anche x86_64 (-Pabi=x86_64)
            abiFilters += ((project.findProperty("abi") as String?)?.split(",") ?: listOf("arm64-v8a"))
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

    packaging {
        resources {
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.md", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    // Parola di attivazione "Jarvis" riconosciuta offline
    implementation("com.alphacephei:vosk-android:0.3.47") {
        exclude(group = "net.java.dev.jna")
    }
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Foto condivise con l'app Fotocamera
    implementation("androidx.core:core:1.13.1")
    // Posizione e promemoria di luogo
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Vista dal vivo: fotocamera sempre aperta
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Voce neurale offline (Piper tramite sherpa-onnx): l'AAR viene scaricato durante la costruzione
    implementation(files("libs/sherpa-onnx.aar"))
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Collaudo automatico
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")

    // Email (IMAP e SMTP)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}
