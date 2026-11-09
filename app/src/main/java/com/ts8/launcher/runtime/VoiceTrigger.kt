package com.ts8.launcher.runtime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The one way Voice Instant is opened, and the only place the microphone
 * permission is asked for.
 *
 * **Why this is a class and not a call to [VoiceInstantController.start] from
 * each screen.** §11 names `VoiceTrigger` as a component, and the reason is the
 * permission dance: on API 23 the first tap must request `RECORD_AUDIO`, the
 * answer arrives asynchronously in `onRequestPermissionsResult`, and the
 * interaction has to start *after* it. Three screens doing that independently
 * would be three chances to start a recogniser with no grant, which on this
 * firmware produces `ERROR_INSUFFICIENT_PERMISSIONS` a second later instead of
 * a dialog.
 *
 * **Why it asks on the mic tap and never at boot.** A launcher that asks for
 * the microphone on first start teaches every driver to deny it. The permission
 * is requested at the moment its purpose is obvious — the driver just tapped a
 * microphone — and a denial is reported through the overlay as
 * `PERMISSION_DENIED`, which is the one error the driver can fix in Settings.
 *
 * **Why `shouldShowRequestPermissionRationale` is not consulted.** On this head
 * unit the driver is in a car, not reading an explanation screen; the overlay
 * already says why the microphone is needed and offers a Settings button. A
 * second dialog explaining the same thing before the system dialog is one more
 * tap between the driver and the command.
 *
 * **Threading.** Main thread only: `requestPermissions` must be called there,
 * and the controller it forwards to drives its recogniser from the main looper.
 */
class VoiceTrigger(
    private val activity: Activity,
    private val controller: VoiceInstantController
) {

    /** True while a permission request this class issued is outstanding. */
    private var awaitingPermission = false

    /**
     * Starts a voice interaction, requesting the microphone permission if it
     * has never been granted.
     *
     * @return true when a session was opened or a permission dialog appeared;
     *         false when an interaction is already running, in which case the
     *         tap is a no-op rather than a second recogniser.
     */
    fun request(): Boolean {
        if (hasPermission()) {
            awaitingPermission = false
            return controller.start()
        }
        if (awaitingPermission) return false
        awaitingPermission = true
        val asked = runCatching {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO
            )
        }.isSuccess
        if (!asked) {
            // A firmware that refuses to show the dialog still has to produce a
            // visible outcome; the overlay's PERMISSION_DENIED path is it.
            awaitingPermission = false
            controller.permissionRefused()
        }
        return asked
    }

    /**
     * Forwards the platform's answer.
     *
     * Called from the owning screen's `onRequestPermissionsResult`. A grant
     * starts the session the driver asked for; a denial is announced through the
     * controller so the overlay shows the Settings affordance, and nothing is
     * retried behind their back.
     *
     * @return true when this call consumed the result.
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != REQUEST_AUDIO) return false
        awaitingPermission = false
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED ||
            hasPermission()
        if (granted) {
            controller.start()
        } else {
            Log.i(TAG, "RECORD_AUDIO denied; voice will report PERMISSION_DENIED")
            controller.permissionRefused()
        }
        return true
    }

    /**
     * Opens the system page for this app's permissions.
     *
     * `ACTION_APPLICATION_DETAILS_SETTINGS` rather than a manufacture-specific
     * intent: the latter is not resolvable on a head unit whose Settings app is
     * the vendor's, and an unresolvable intent would leave the driver with a
     * dead button on the one error they can fix.
     */
    fun openPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { activity.startActivity(intent) }
        if (opened.isFailure) Log.w(TAG, "permission settings unavailable on this firmware")
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        activity as Context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Ts8VoiceTrigger"

        /**
         * Request code for `RECORD_AUDIO`.
         *
         * Distinct from every other code in the app (location is `0x7411`,
         * the launcher's own first-run set is its own) so a screen forwarding
         * two results cannot confuse them.
         */
        const val REQUEST_AUDIO = 0x7A11
    }
}
