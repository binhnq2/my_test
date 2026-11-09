plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.location"
}

dependencies {
    api(project(":core:map-api"))
}
