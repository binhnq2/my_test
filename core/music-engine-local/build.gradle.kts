plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.music.local"
}

dependencies {
    api(project(":core:music-api"))
    // The player takes audio focus through the bridge rather than talking to
    // AudioManager directly, so a future SyuAudioBridge is a constructor change.
    api(project(":core:vehicle-audio-bridge"))
}
