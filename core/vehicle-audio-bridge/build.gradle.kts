plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.audio"
}

dependencies {
    api(project(":core:music-api"))
}
