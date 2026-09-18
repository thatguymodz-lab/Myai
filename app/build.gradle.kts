plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.novajarvis.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novajarvis.android"

        minSdk = 26
        targetSdk = 35

        versionCode = 3
        versionName = "0.3"

        ndk {
            abiFilters += listOf(
                "arm64-v8a"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation(
        "dev.ffmpegkit-maintained:llama-android:0.1.1"
    )
}
