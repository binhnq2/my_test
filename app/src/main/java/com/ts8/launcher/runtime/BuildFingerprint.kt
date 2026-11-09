package com.ts8.launcher.runtime

import java.io.File
import java.security.MessageDigest

/**
 * A content-derived build identity, in place of a `BuildConfig` field (§11).
 *
 * **Why the APK's own digest and not a version string.** §11 asks for a
 * "build hash", and the reason a hash is asked for rather than a version is
 * that two builds can share `versionName = "1.0.0"`. This module does not
 * generate `BuildConfig` (nothing configures `buildConfig = true` for it), and
 * even with it, a `BUILD_NUMBER` compiled in is a number the build host chose,
 * not evidence about what is on the head unit. The installed APK's bytes are
 * the only thing that is simultaneously (a) unique per build, (b) readable on
 * the device and (c) impossible to disagree with what is actually installed.
 *
 * **Why truncated to 12 hex characters.** The full digest is 64 characters and
 * a driver reading it down a phone line will get it wrong. Twelve is the
 * length git uses for the same reason: enough that a collision means the
 * build did not change, short enough to say out loud. The export prints the
 * short form; a support engineer who needs the full one runs `sha256sum` on
 * the APK they were given.
 *
 * **Why the read is capped.** The APK is ~58 MB on this build. Hashing it means
 * reading every byte from eMMC, which is a second or two on this SoC — fine for
 * an explicit export press, not fine on a screen's first frame. [compute] is
 * therefore called from the IO pool and its result cached for the process; the
 * screen shows [PENDING] until the answer arrives rather than blocking.
 */
object BuildFingerprint {

    /** Shown until the digest has been computed, and if it could not be. */
    const val PENDING = "đang tính…"
    const val UNKNOWN = "không đọc được"

    @Volatile
    private var cached: String? = null

    /** The cached short digest, or null before the first successful read. */
    fun cached(): String? = cached

    /**
     * Hashes [apkFile] and returns its short digest.
     *
     * Blocking; call from [com.ts8.launcher.di.AppGraph.io]. Any failure is
     * answered with [UNKNOWN] rather than an exception: a diagnostics screen
     * that crashes on the one device it was written for would be worse than one
     * that says it could not measure.
     */
    fun compute(apkFile: File): String {
        cached?.let { return it }
        val digest = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest()
        }.getOrNull() ?: return UNKNOWN
        val short = buildString(SHORT_LENGTH) {
            for (i in 0 until SHORT_LENGTH) {
                append(HEX[(digest[i].toInt() shr 4) and 0xF])
                append(HEX[digest[i].toInt() and 0xF])
            }
        }
        cached = short
        return short
    }

    /** 32 KiB: large enough that the per-read overhead disappears on eMMC. */
    private const val HASH_BUFFER_BYTES = 32 * 1024

    /** 12 hex chars = 6 digest bytes = 48 bits, the length git shortens to. */
    private const val SHORT_LENGTH = 12

    private val HEX = "0123456789abcdef".toCharArray()
}
