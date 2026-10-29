plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.focuslock.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.focuslock.zen"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    signingConfigs {
        create("releaseKey") {
            val ksFile = file("release.p12")
            if (ksFile.exists()) {
                storeFile = ksFile
                storeType = "PKCS12"
                storePassword = "focuslock_release_2026"
                keyAlias = "focuslock_key"
                keyPassword = "focuslock_release_2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            val ksFile = file("release.p12")
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("releaseKey")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            val ksFile = file("release.p12")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("releaseKey")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
