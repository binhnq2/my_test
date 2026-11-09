package com.ts8.launcher.runtime

import android.content.Context
import android.content.SharedPreferences

/**
 * `SharedPreferences` as the two platform-free get/put pairs the core modules
 * declare.
 *
 * **Why one class implements both interfaces.** `com.ts8.core.voice.KeyValueStore`
 * and `com.ts8.core.parking.KeyValueStore` are declared in two modules that must
 * not depend on each other, and both are exactly `get(key): String?` plus
 * `put(key, value)`. Their signatures are identical, so a single adapter can
 * satisfy both and the app does not end up with two near-identical private
 * preference files with two different corruption behaviours.
 *
 * **Why a separate prefs file from `AppSettings`.** The settings file is what a
 * driver configures; this one is what the app remembers about its own last
 * action (`voice.last_state`, `parking_last_decision_v1`). Keeping the last
 * voice state out of the settings file means a driver who clears a setting
 * cannot accidentally clear the record that explains what the screen was doing.
 *
 * **Why writes are `commit()` and not `apply()`.** Both stores are written at
 * the end of an interaction — exactly the moment before the head unit may lose
 * power with the ignition. `apply()` is asynchronous and a process killed
 * before its background write lands leaves the old row, which is the stuck
 * overlay `VoiceStateStore` exists to prevent. These writes happen a handful of
 * times per interaction, never per frame, so the synchronous cost is a few
 * hundred microseconds on a path that already stops the microphone.
 *
 * **Why failures are swallowed.** A preferences write can fail on a full or
 * read-only data partition. There is nothing the driver could do about it and
 * no screen that should stop working because of it: the store's contract is
 * "a failed write degrades to no state", and a crash here would be worse than
 * the missing row.
 */
class PrefsKeyValueStore(
    context: Context,
    fileName: String = PREFS
) : com.ts8.core.voice.KeyValueStore,
    com.ts8.core.parking.KeyValueStore,
    com.ts8.launcher.runtime.StateStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun get(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    override fun put(key: String, value: String) {
        runCatching { prefs.edit().putString(key, value).commit() }
    }

    companion object {
        /**
         * Not `ts8_settings`: see the note above on why the two are separate.
         */
        const val PREFS = "ts8_runtime_state"
    }
}
