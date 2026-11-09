plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.navigation"
}

dependencies {
    api(project(":core:map-api"))
    api(project(":core:location-engine"))
    api(project(":core:routing-engine-offline"))
    api(project(":core:place-search"))
    api(project(":core:music-api"))
    api(project(":core:vehicle-audio-bridge"))
}
