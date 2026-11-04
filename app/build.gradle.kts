import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.sdwinnie.airgesture"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sdwinnie.airgesture"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "6.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    androidResources {
        noCompress += listOf("bin", "dat", "task", "tflite")
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore/air-gesture.jks")
            val storePw = localProps.getProperty("AIR_GESTURE_STORE_PW")
                ?: System.getenv("AIR_GESTURE_STORE_PW")
            if (ksFile.exists() && !storePw.isNullOrBlank()) {
                storeFile = ksFile
                storePassword = storePw
                keyAlias = localProps.getProperty("AIR_GESTURE_KEY_ALIAS") ?: "airgesture"
                keyPassword = localProps.getProperty("AIR_GESTURE_KEY_PW") ?: storePw
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSign = signingConfigs.getByName("release")
            signingConfig = if (releaseSign.storeFile?.exists() == true) {
                releaseSign
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            val releaseSign = signingConfigs.getByName("release")
            if (releaseSign.storeFile?.exists() == true) {
                signingConfig = releaseSign
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

    packaging {
        jniLibs {
            // Ensures clean native .so extraction on Android 14/15/16 (including 16KB page-size devices)
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // 100% On-Device Edge-AI Hand Landmark Vision Engine (Bundled .task model, zero cloud)
    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    testImplementation("junit:junit:4.13.2")
}
