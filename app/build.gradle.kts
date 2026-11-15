plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.nexumcell.trava"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.nexumcell.trava"
        minSdk = 26          // Android 8.0+
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 2
        versionName = "1.2"
        buildConfigField("String", "UPDATE_API", "\"https://api.github.com/repos/nexumrh/trava/releases/latest\"")
    }

    // Assinatura: o GitHub Actions passa a chave pelos secrets (nunca fica no repositório)
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
            // assinatura v1 (JAR) também: o instalador do QR de alguns aparelhos (Realme, Oppo…) só confere a v1
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.zxing:core:3.5.3")
    // leitor de QR do Google (Play Services): ativa a instalação por link lendo o QR da tela do vendedor
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // aviso instantâneo (FCM) — iniciado à mão, sem google-services.json
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")
}
