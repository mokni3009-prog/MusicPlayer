plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.musicplayer.app"
    // compileSdk 36 is required by libVLC 3.7.8 (its AAR declares
    // minCompileSdk 36); targetSdk stays 34 (no new runtime behaviour).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musicplayer.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.7"
    }

    buildToolsVersion = "36.0.0"

    // libVLC ships native libraries for every ABI — split the APK per ABI
    // so each device downloads only its own native code.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            // Release keystore is decoded from the RELEASE_KEYSTORE_BASE64
            // GitHub secret at build time; never committed to the repo.
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.guava:guava:32.1.2-android")

    // Media3 — local audio playback + media session (background playback + notification)
    // NOTE: staying on 1.4.1 (known good with this codebase); newer
    // Media3 needs a newer compileSdk, which we now have (36), but the
    // upgrade is out of scope for v1.0.4.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    // libVLC — secondary engine for rare audio formats Media3 cannot read
    // (APE, DSD, WavPack, MPC, tracker, WMA...). Media3 stays the primary
    // engine; VLC is used only as a fallback, switched automatically.
    implementation("org.videolan.android:libvlc-all:3.7.8")

    // jaudiotagger — batch tag editing (title/artist/album/genre/year/cover)
    implementation("net.jthink:jaudiotagger:3.0.1")
}
