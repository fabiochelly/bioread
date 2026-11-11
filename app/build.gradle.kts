import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "app.bioread"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.bioread"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        // ML Kit embarque du code natif : on ne garde que les architectures utiles.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Clé de signature de l'app : keystore/keystore.properties (hors dépôt, à sauvegarder).
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += "x86_64" }   // émulateur
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Clé de l'app si elle est présente, sinon clé debug (build sur une autre machine).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
}
