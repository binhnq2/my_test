plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.routing"
}

dependencies {
    api(project(":core:map-api"))
}
