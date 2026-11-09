plugins {
    id("ts8.core-module")
}

android {
    namespace = "com.ts8.core.map.maplibre"
}

dependencies {
    // The rendering contract (`MapController`, `GeoPoint`, `MapStyleMode`, ...).
    // `api` rather than `implementation` because a caller that constructs this
    // engine necessarily already speaks the contract types.
    api(project(":core:map-api"))

    // MapLibre Native 12.0.0 is the last major line that still:
    //   * declares android:minSdkVersion="23" — exactly the Android 6 / API 23
    //     target of the TS8 head unit — and
    //   * renders through OpenGL ES rather than requiring Vulkan 1.0.
    //     13.x adds <uses-feature android:name="android.hardware.vulkan.version"
    //     required="true"/>, which this GPU does not advertise.
    //
    // Verified against the published 12.0.0 AAR, not assumed: the native
    // `DefaultFileSourceManagerImpl` registers `MBTilesFileSource` for
    // `FileSourceType::Mbtiles` unconditionally, and the RTTI typeinfo for
    // `mbgl::MBTilesFileSource` is present in all four ABIs
    // (armeabi-v7a, arm64-v8a, x86, x86_64). So `mbtiles://` works on every ABI
    // this APK ships, and the map pack needs no embedded HTTP server.
    //
    // The geojson/turf artifacts are pulled transitively at their own version
    // line — they are independent of the SDK's major version.
    api("org.maplibre.gl:android-sdk:12.0.0")
}
