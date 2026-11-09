plugins {
    id("org.jetbrains.kotlin.android")
    id("com.android.library")
}

android {
    namespace = "com.ts8.core.commercial"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
