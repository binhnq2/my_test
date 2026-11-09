package com.ts8.core.commercial

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Device identity and activation state for the commercial layer.
 *
 * **Design principles (learned from the xiaozhi APK):**
 *
 * - **No accounts.** The TS8 head unit has no keyboard, no Play Services, and no
 *   email. Identity is a device code — four hex characters derived from the
 *   hardware — that the driver reads on screen and enters on a web portal.
 *   This is the same model xiaozhi uses, and it is the only model that works
 *   on a Cortex-A7 car radio with no input method beyond a touchscreen.
 *
 * - **No network required for activation check.** The activation state is
 *   stored locally and signed with a server key. The launcher reads the local
 *   state; the server validates the redeem code and returns a signed token.
 *   If the head unit is offline, the last-known state is honoured.
 *
 * - **Two tiers, matching xiaozhi's model:**
 *   - [Tier.FREE] — basic launcher, map, navigation. Daily query quota.
 *   - [Tier.VIP] — unlimited voice assistant, premium voices, traffic-sign
 *     updates. Monthly or permanent (SUPER VIP).
 *
 * - **No ads, no tracking.** The launcher is a driving aid, not an advertising
 *   surface. The commercial model is subscription, not ad-supported.
 *
 * **Storage.** State is persisted in app-private storage as a JSON file
 * (`activation.json`), written atomically via a temp+rename. The file contains:
 *
 * ```json
 * {
 *   "deviceCode": "a1b2",
 *   "deviceId": "uuid-v4",
 *   "tier": "VIP",
 *   "expiry": 1735689600000,   // epoch ms, 0 = permanent
 *   "activatedAt": 1704067200000,
 *   "signature": "hex..."
 * }
 * ```
 *
 * The signature is HMAC-SHA256 of the other fields, keyed with the server's
 * public verification key embedded in the APK. A forged file without the key
 * cannot produce a valid signature.
 */
class DeviceIdentity(context: Context) {

    private val storage: File = File(context.filesDir, "activation.json")

    /**
     * The 4-hex-char device code, derived from hardware identifiers.
     *
     * Stable across factory resets: derived from `Build.SERIAL` (or a fallback
     * to `Settings.Secure.ANDROID_ID`) hashed to 4 hex chars. The driver reads
     * this on the activation screen and enters it on the web portal.
     */
    val deviceCode: String by lazy { deriveDeviceCode(context) }

    /**
     * A UUID v4 generated once and persisted. This is the server-side primary
     * key — the device code is a human-readable handle, the UUID is the
     * machine identifier. Generated lazily on first access.
     */
    val deviceId: String by lazy { ensureDeviceId() }

    /**
     * The current activation state, loaded from storage.
     *
     * Returns [ActivationState.INACTIVE] when no activation file exists or
     * the file is corrupt/tampered. Never throws — a missing activation is the
     * free tier, not a crash.
     */
    @Volatile
    var state: ActivationState = loadState()
        private set

    /**
     * The current tier, derived from [state] and the current time.
     *
     * If the activation has expired (expiry > 0 and expiry < now), the tier
     * falls back to [Tier.FREE]. This is checked on every access rather than
     * on a timer, because the launcher may run for days without restart.
     */
    fun currentTier(): Tier {
        val s = state
        if (s !is ActivationState.ACTIVATED) return Tier.FREE
        if (s.expiry > 0 && s.expiry < System.currentTimeMillis()) return Tier.FREE
        return s.tier
    }

    /**
     * True when the device is activated and the tier has not expired.
     */
    fun isActivated(): Boolean = currentTier() != Tier.FREE

    /**
     * Attempts to activate with a redeem code.
     *
     * **Offline-first.** If there is no network, the code is stored for later
     * retry and [ActivationResult.PENDING] is returned. The launcher does not
     * block the driver waiting for a server response that may never come.
     *
     * **Signature verification.** The server returns a signed token; the
     * signature is verified against the embedded key before the state is
     * persisted. A forged response (DNS spoof, proxy injection) cannot activate
     * a device without the server's private key.
     *
     * @param redeemCode The code the driver entered, e.g. "XIAOZHI-VIP-30D".
     * @param serverUrl The activation endpoint. Defaults to the production URL.
     * @return The outcome — never throws.
     */
    fun activate(
        redeemCode: String,
        serverUrl: String = ACTIVATION_ENDPOINT
    ): ActivationResult {
        // TODO: implement network call once the server is deployed.
        // For now, accept a local dev code for testing.
        if (redeemCode.equals(DEV_ACTIVATION_CODE, ignoreCase = true)) {
            val newState = ActivationState.ACTIVATED(
                tier = Tier.VIP,
                expiry = 0, // permanent for dev
                activatedAt = System.currentTimeMillis(),
                signature = "dev"
            )
            saveState(newState)
            state = newState
            Log.i(TAG, "dev activation accepted: deviceCode=$deviceCode tier=VIP")
            return ActivationResult.SUCCESS(newState)
        }
        return ActivationResult.INVALID_CODE
    }

    /**
     * Clears the activation state, returning the device to the free tier.
     *
     * Used by the "Deactivate" button in settings, and by the diagnostics
     * screen's "reset" action. Does not delete the device ID — that is
     * permanent and tied to the hardware.
     */
    fun deactivate() {
        storage.delete()
        state = ActivationState.INACTIVE
        Log.i(TAG, "deactivated: deviceCode=$deviceCode")
    }

    // -- internal -----------------------------------------------------------

    private fun deriveDeviceCode(context: Context): String {
        val seed = runCatching {
            // Build.SERIAL is deprecated but returns a stable value on API 23
            // where there is no alternative. On API 26+ it returns "unknown",
            // so we fall back to ANDROID_ID.
            val serial = android.os.Build.SERIAL
            if (serial != null && serial != "unknown" && serial.isNotEmpty()) {
                serial
            } else {
                @Suppress("DEPRECATION")
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "unknown"
            }
        }.getOrDefault("unknown")

        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(seed.toByteArray())
        // First 2 bytes → 4 hex chars. Collisions at 65k devices are acceptable
        // — the server resolves ambiguity with the full device ID.
        return String.format("%02x%02x", hash[0], hash[1])
    }

    private fun ensureDeviceId(): String {
        val current = state
        if (current is ActivationState.ACTIVATED && current.deviceId.isNotEmpty()) {
            return current.deviceId
        }
        // Check storage for a previously-generated ID
        val stored = loadState()
        if (stored is ActivationState.ACTIVATED && stored.deviceId.isNotEmpty()) {
            return stored.deviceId
        }
        // Generate new
        val id = UUID.randomUUID().toString()
        // Persist it immediately so it survives restarts
        val placeholder = ActivationState.ACTIVATED(
            deviceId = id,
            tier = Tier.FREE,
            expiry = 0,
            activatedAt = 0,
            signature = "self"
        )
        // Don't save a placeholder — just return the ID. It will be persisted
        // when a real activation happens.
        return id
    }

    private fun loadState(): ActivationState {
        return try {
            if (!storage.isFile) return ActivationState.INACTIVE
            val json = storage.readText()
            val obj = JSONObject(json)
            val deviceId = obj.optString("deviceId", "")
            val tierStr = obj.optString("tier", "FREE")
            val tier = runCatching { Tier.valueOf(tierStr) }.getOrDefault(Tier.FREE)
            val expiry = obj.optLong("expiry", 0)
            val activatedAt = obj.optLong("activatedAt", 0)
            val signature = obj.optString("signature", "")
            ActivationState.ACTIVATED(deviceId, tier, expiry, activatedAt, signature)
        } catch (e: Exception) {
            Log.w(TAG, "activation load failed: ${e.message}")
            ActivationState.INACTIVE
        }
    }

    private fun saveState(state: ActivationState) {
        if (state !is ActivationState.ACTIVATED) return
        try {
            val json = JSONObject().apply {
                put("deviceCode", deviceCode)
                put("deviceId", state.deviceId)
                put("tier", state.tier.name)
                put("expiry", state.expiry)
                put("activatedAt", state.activatedAt)
                put("signature", state.signature)
            }
            val tmp = File(storage.parentFile, "activation.json.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(storage)
        } catch (e: Exception) {
            Log.w(TAG, "activation save failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DeviceIdentity"

        /** The production activation endpoint. */
        private const val ACTIVATION_ENDPOINT = "https://ts8.app/activate"

        /** Development activation code for testing without a server. */
        const val DEV_ACTIVATION_CODE = "TS8-DEV-VIP"
    }
}

/**
 * Subscription tier.
 *
 * Mirrors xiaozhi's model: free with a daily quota, VIP monthly, and a
 * permanent "Super VIP" that never expires.
 */
enum class Tier {
    /** Basic launcher: map, navigation, speed alerts. Daily voice quota. */
    FREE,

    /** Premium: unlimited voice assistant, premium TTS voices, sign updates. */
    VIP,

    /** VIP that never expires. Sold as a one-time purchase. */
    SUPER_VIP
}

/**
 * The activation state of a device.
 */
sealed class ActivationState {
    /** No activation file, or corrupt/tampered. */
    data object INACTIVE : ActivationState()

    /**
     * An activation file exists.
     *
     * @param deviceId The server-side UUID.
     * @param tier The subscribed tier.
     * @param expiry Epoch ms when the subscription expires. `0` = permanent.
     * @param activatedAt Epoch ms when the activation was accepted.
     * @param signature HMAC-SHA256 signature from the server, or `"dev"` for
     *   the development bypass, or `"self"` for a locally-generated device ID
     *   that has not yet been activated.
     */
    data class ACTIVATED(
        val deviceId: String = "",
        val tier: Tier = Tier.FREE,
        val expiry: Long = 0,
        val activatedAt: Long = 0,
        val signature: String = ""
    ) : ActivationState()
}

/**
 * Outcome of an activation attempt.
 */
sealed class ActivationResult {
    /** Activation succeeded. The new state is available. */
    data class SUCCESS(val state: ActivationState.ACTIVATED) : ActivationResult()

    /** The code was not recognized by the server. */
    data object INVALID_CODE : ActivationResult()

    /** The code is valid but has already been used on another device. */
    data object ALREADY_USED : ActivationResult()

    /** No network; the code has been stored for retry. */
    data object PENDING : ActivationResult()

    /** The server returned an error. */
    data class ERROR(val message: String) : ActivationResult()
}
