plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credenciais de assinatura NUNCA ficam no repositório.
// Fornecidas por variáveis de ambiente (CI) ou por ~/.gradle/gradle.properties / -P (local):
//   FOCUSLOCK_KEYSTORE_FILE, FOCUSLOCK_KEYSTORE_PASSWORD, FOCUSLOCK_KEY_ALIAS, FOCUSLOCK_KEY_PASSWORD
// Se FOCUSLOCK_KEYSTORE_FILE não for definido, usa app/release.p12 (ignorado pelo git) quando existir.
fun signingProp(name: String): String? =
    (System.getenv(name) ?: (project.findProperty(name) as String?))?.takeIf { it.isNotBlank() }

val releaseKeystore: File? = (signingProp("FOCUSLOCK_KEYSTORE_FILE")?.let { file(it) }
    ?: file("release.p12")).takeIf { it.exists() }
val releaseStorePassword = signingProp("FOCUSLOCK_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProp("FOCUSLOCK_KEY_ALIAS") ?: "focuslock_key"
val releaseKeyPassword = signingProp("FOCUSLOCK_KEY_PASSWORD") ?: releaseStorePassword
val hasReleaseSigning = releaseKeystore != null && releaseStorePassword != null

android {
    namespace = "com.focuslock.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.focuslock.zen"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "3.7"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("releaseKey") {
                storeFile = releaseKeystore
                storeType = "PKCS12"
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = if (hasReleaseSigning) {
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
            if (hasReleaseSigning) {
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
