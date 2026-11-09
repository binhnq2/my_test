pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TS8CarLauncher"

include(":app")

include(":core:map-api")
include(":core:map-engine-maplibre")
include(":core:place-search")
include(":core:routing-engine-offline")
include(":core:location-engine")
include(":core:navigation-core")
include(":core:music-api")
include(":core:music-engine-local")
include(":core:music-provider-online")
include(":core:vehicle-audio-bridge")
include(":core:diagnostics")
include(":core:voice-offline")
include(":core:commercial")
