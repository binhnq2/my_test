plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.music.online"
}

dependencies {
    api(project(":core:music-api"))
}
