# R8 rules for the release build.
#
# The debug build does not use this file (`isMinifyEnabled = false`), and the
# acceptance target of this campaign is still a debug APK. The release build has
# nevertheless been run on this host (`gradlew :app:assembleRelease --offline`),
# so a rule that is syntactically wrong would already have failed here rather
# than on the build machine that ships it.

# ---------------------------------------------------------------- MapLibre
#
# MapLibre's Java layer is reached from native code through JNI, so the class and
# method names the C++ side looks up by string cannot be renamed.
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# The JNI layer looks up these by name; the MapLibre geojson/turf artifacts keep
# the old `com.mapbox` package prefix in some builds and `org.maplibre` in
# others, so both spellings are kept. A `-keep` for a package that does not
# exist is not an error — R8 reports it as an unused rule at most.
-keep class org.maplibre.geojson.** { *; }
-keep class org.maplibre.turf.** { *; }
-keep class com.mapbox.geojson.** { *; }
-keep class com.mapbox.turf.** { *; }
-dontwarn com.mapbox.**

# OkHttp is MapLibre's transitive dependency and is never called by this app
# (see THIRD_PARTY_LICENSES.md §1.4). Keeping its optional platform hooks out of
# the way avoids warnings for the Conscrypt and BouncyCastle integrations, which
# are absent on API 23.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --------------------------------------------------------------------- Vosk
#
# `org.vosk.Model`/`org.vosk.Recognizer` are thin JNA bindings: every method body
# is a native call that JNA resolves through `Native.load` plus a method-name →
# symbol lookup. R8 renaming either the class or its methods breaks that lookup
# at *run time*, with an `UnsatisfiedLinkError` that mentions a symbol nobody
# wrote, on a release build only — the debug build does not run R8 at all.
#
# `com.sun.jna` is kept for the same reason one level down: JNA builds its
# `Structure` field layouts and callback stubs from reflection over field names
# and order, so an obfuscated `Structure` subclass is a silently wrong memory
# layout rather than a build error.
#
# These rules were checked by measurement, not by a green build. Building the
# release with the `# ---- Vosk` block removed keeps only 2 `org.vosk.*` types
# (down from 20) and 6 `com.sun.jna.*` types (down from 127), and turns
# `org.vosk.Recognizer` into `q2.a` — while `VoskOfflineRecognitionProvider`
# calls `new Recognizer(...)`, so that APK would throw `NoClassDefFoundError` the
# first time voice is used. Putting the block back reproduces the original APK
# byte for byte. Note that native *method* names survive either way, because
# `proguard-android-optimize.txt` already keeps `native <methods>`; it is the
# class name that JNA loses. See `docs/VOICE_OFFLINE_SANDBOX_TEST.md` §7.
#
# The one thing still unproven is a device run: R8 may reorder a `Structure`
# subclass's fields, or shrink something only native code calls. Symptom if so
# is a link error on the release build only.
-keep class org.vosk.** { *; }
-keep interface org.vosk.** { *; }
-dontwarn org.vosk.**
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
# JNA discovers the platform backend by class name at run time.
-keep class * implements com.sun.jna.TypeMapper { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.CallbackReference { *; }

# ------------------------------------------------------- reflective lookups
#
# `MapPack` and `MusicPlaybackService` are named in the manifest, and the
# `StorageVolume` handling in `UsbMediaReceiver` calls into a platform class by
# reflection (API 24+ on an API 23 device — see ANDROID_6_COMPATIBILITY.md §3.1).
-keep class com.ts8.launcher.service.** { *; }
-keep class com.ts8.launcher.Ts8LauncherApp { *; }
-keep class com.ts8.launcher.ui.**Activity { *; }

# Kotlin metadata is read by the Kotlin reflection path in a few places; keeping
# it costs little and an obfuscated `@Metadata` is a runtime crash that reads
# like a source bug.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep class kotlin.Metadata { *; }

# --------------------------------------------------------------- diagnostics
#
# A stack trace from the field has to name the class. Keep the line numbers, and
# keep the source file name so a report can be mapped back to a file.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
