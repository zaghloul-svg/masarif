plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing.
//
// The first build signed release with the DEBUG key, so the APK carried
// "CN=Android Debug". Play Protect treats that as a red flag, especially on an
// app requesting SMS permissions, and blocked the install.
//
// A real key also matters beyond that warning: Android only accepts an update
// if it is signed with the same key as the installed app. A stable key means
// future builds install over the top instead of forcing an uninstall, which
// would wipe your transaction history.
//
// The keystore is never committed. CI writes it from a repository secret; if no
// secret is set the build falls back to the debug key so local builds still work.
val releaseKeystore = rootProject.file("masarif-release.jks")
val releaseKeystorePassword: String? = System.getenv("MASARIF_KEYSTORE_PASSWORD")
val hasReleaseKey = releaseKeystore.exists() && !releaseKeystorePassword.isNullOrBlank()

android {
    namespace = "com.zaghloul.masarif"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zaghloul.masarif"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("MASARIF_KEY_ALIAS") ?: "masarif"
                keyPassword = System.getenv("MASARIF_KEY_PASSWORD") ?: releaseKeystorePassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (hasReleaseKey) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
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
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // The parser, categorizer and insight engine are pure Kotlin, so they are
    // covered by plain JVM unit tests — no emulator, and they run on every build.
    testImplementation("junit:junit:4.13.2")
}
