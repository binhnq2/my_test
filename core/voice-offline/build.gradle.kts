plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.voice.offline"
}

dependencies {
    // The closed `VoiceError` set. Deliberately `api`: a caller that handles a
    // recognition outcome necessarily already speaks this vocabulary.
    api(project(":core:navigation-core"))
}
