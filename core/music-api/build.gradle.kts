plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.music"
}

dependencies {
    // For GeoPoint on TrackPlace.
    api(project(":core:map-api"))
}
