// Plugin versions come from buildSrc/build.gradle.kts, which puts AGP and the
// Kotlin plugin on the classpath. Declaring a version again here fails the
// build with "plugin is already on the classpath with an unknown version".
plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
}
