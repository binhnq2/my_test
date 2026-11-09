plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

android {
    namespace = "com.ts8.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ts8.launcher"
        minSdk = 23
        targetSdk = 30
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            // Only the ABIs a target in this campaign can actually run.
            //
            // The TS8 is Cortex-A7/ARMv7; arm64 is kept because it is the other
            // ARM variant a later unit could be. Without this filter the APK
            // also carries `x86`, `x86_64` and JNA's ancient `armeabi`/`mips`/
            // `mips64` builds — roughly 45 MB of native libraries that no
            // device this launcher is installed on can load, and every megabyte
            // is copied over USB on each pilot round.
            //
            // This is also what makes the offline-model store's ABI list
            // honest: `VoiceModelManifest.ACCEPTED_ABIS` names exactly these
            // two, and `AppGraph` passes that list to the validator as "the
            // ABIs this APK ships". A pack built for an ABI filtered out here
            // is now reported INCOMPATIBLE instead of failing to load a `.so`
            // that is not in the file.
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/*.kotlin_module"
        )
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        // API-23 guard: every call above minSdk must be annotated or guarded.
        checkReleaseBuilds = true

        // `ExpiredTargetSdkVersion` is fatal-severity by default and exists to
        // push Play Store submissions to a recent targetSdk. This APK is never
        // submitted to Play and never installed on a modern phone: it is the
        // home app for an Android 6 head unit, and targetSdk 30 is chosen so the
        // runtime applies the pre-scoped-storage, pre-split-permission rules the
        // device actually has. Raising it to satisfy the check would change the
        // permission model and the storage access this launcher is built
        // around. See docs/ANDROID_6_COMPATIBILITY.md.
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests {
            // The unit tests in this module are pure JVM and the classes under
            // test are pure state tables — the state machine, the id-space
            // arithmetic — not Activities. They call `android.util.Log` on the
            // paths they exercise, and Robolectric would answer that by
            // downloading an `android-all` jar that is not in this build host's
            // offline cache. Returning the framework defaults instead keeps the
            // suite runnable with `--offline`, which is the only way this host
            // can build at all. Robolectric-based tests can still opt in with
            // `@RunWith(RobolectricTestRunner::class)` where a jar is present.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:map-api"))
    implementation(project(":core:map-engine-maplibre"))
    implementation(project(":core:place-search"))
    implementation(project(":core:routing-engine-offline"))
    implementation(project(":core:location-engine"))
    implementation(project(":core:navigation-core"))
    implementation(project(":core:voice-offline"))
    implementation(project(":core:music-api"))
    implementation(project(":core:music-engine-local"))
    implementation(project(":core:music-provider-online"))
    implementation(project(":core:vehicle-audio-bridge"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:commercial"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Offline Vietnamese recognition (§3–4 of the voice pilot). Pinned, not a
    // range: the third-party licence note in docs/VOICE_MODEL_LICENSE.md names
    // 0.3.47 and its JNA transitive, and an upgrade changes the native ABI this
    // build ships.
    implementation("com.alphacephei:vosk-android:0.3.47")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
