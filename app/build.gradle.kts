plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fireairplay.receiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fireairplay.receiver"
        // Fire OS is based on Android API 25-30 depending on device generation.
        // Using 22 for broad compatibility with older Fire TV Sticks.
        minSdk = 22
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ConstraintLayout for TV-optimized layout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CardView for album art with rounded corners
    implementation("androidx.cardview:cardview:1.0.0")

    // Palette — extract dominant colors from album art for animated background
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Lifecycle — ViewModel + LiveData for reactive UI updates
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
