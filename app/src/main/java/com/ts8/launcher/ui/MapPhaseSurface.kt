package com.ts8.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.ts8.launcher.R
import com.ts8.launcher.runtime.MapPhaseStore

/**
 * Binds `view_map_phase.xml` to a [MapPhaseStore] (§6).
 *
 * **Why this exists as a class rather than a method on each screen.** Home and
 * Map draw the same six phases, the same error code, the same pack line and the
 * same three actions. Two implementations would drift, and the one that drifted
 * would be the screen the driver happened to be looking at.
 *
 * **What the three actions do, and what they refuse to do.**
 *
 * * `Thử lại` calls [onRetry] — the screen's own rebuild — and separately resets
 *   the store, because a store still in `FAILED` would repaint the error the
 *   instant the screen re-registered.
 * * `Cài đặt bản đồ` goes through [MapManagementActivity.start], which returns
 *   false rather than throwing. On a build where the Activity is somehow
 *   absent the button must not take the whole map screen down.
 * * `Chi tiết` shows what the one-line message has no room for: the reason in
 *   full, the error code, and the pack. It is an `AlertDialog` rather than a
 *   screen because it is a sentence and no state.
 *
 * **Why the progress line is not a spinner over a blank map.** It replaces the
 * map's own area only while the renderer has produced nothing; once the first
 * frame arrives the store moves to `READY` and this whole surface goes `GONE`.
 * A driver never sees a spinner on top of a working map.
 */
class MapPhaseSurface(
    private val activity: Activity,
    private val root: View,
    private val store: MapPhaseStore,
    /** Rebuilds the map; called by `Thử lại`. */
    private val onRetry: () -> Unit
) {

    private val progress: View = root.findViewById(R.id.mapPhaseProgress)
    private val progressText: TextView = root.findViewById(R.id.mapPhaseProgressText)
    private val progressPack: TextView = root.findViewById(R.id.mapPhasePackText)
    private val error: View = root.findViewById(R.id.mapPhaseError)
    private val errorDetail: TextView = root.findViewById(R.id.mapPhaseErrorDetail)
    private val errorCode: TextView = root.findViewById(R.id.mapPhaseErrorCode)
    private val errorPack: TextView = root.findViewById(R.id.mapPhaseErrorPack)

    private val listener: (MapPhaseStore.Snapshot) -> Unit = { render(it) }

    init {
        root.findViewById<Button>(R.id.btnMapRetry).setOnClickListener {
            store.reset()
            onRetry()
        }
        root.findViewById<Button>(R.id.btnMapManage).setOnClickListener {
            if (!MapManagementActivity.start(activity)) {
                // Nothing else can be done from here, and silence would read as
                // a dead button. The error surface already carries the reason
                // the map is not drawing; this only says the second screen
                // could not open on this build.
                showDetailDialog(
                    store.snapshot.detail
                        ?: activity.getString(R.string.map_error_title),
                    store.snapshot
                )
            }
        }
        root.findViewById<Button>(R.id.btnMapDetails).setOnClickListener {
            showDetailDialog(
                store.snapshot.detail ?: activity.getString(R.string.map_error_title),
                store.snapshot
            )
        }
        // Registers and renders the current value in one call.
        store.addListener(listener)
    }

    fun detach() {
        store.removeListener(listener)
    }

    private fun render(snapshot: MapPhaseStore.Snapshot) {
        // READY hides everything: the map is drawing and this surface would be
        // a scrim over it.
        if (snapshot.isReady) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE
        progress.visibility = if (snapshot.isBusy) View.VISIBLE else View.GONE
        error.visibility = if (snapshot.isFailed) View.VISIBLE else View.GONE

        if (snapshot.isBusy) {
            progressText.setText(
                when (snapshot.phase) {
                    MapPhaseStore.Phase.INITIALIZING -> R.string.map_phase_initializing
                    MapPhaseStore.Phase.VALIDATING_PACK -> R.string.map_phase_validating_pack
                    MapPhaseStore.Phase.LOADING_STYLE -> R.string.map_phase_loading_style
                    else -> R.string.map_phase_waiting_first_frame
                }
            )
            val pack = snapshot.packName
            if (pack.isNullOrBlank()) {
                progressPack.visibility = View.GONE
            } else {
                progressPack.visibility = View.VISIBLE
                progressPack.text = activity.getString(R.string.map_error_pack, pack)
            }
            return
        }

        errorDetail.text = snapshot.detail ?: activity.getString(R.string.map_error_title)
        errorCode.text = activity.getString(
            R.string.map_error_code,
            snapshot.errorCode ?: MapPhaseStore.UNKNOWN_CODE
        )
        val pack = snapshot.packName
        errorPack.text = if (pack.isNullOrBlank()) {
            activity.getString(R.string.map_error_pack_none)
        } else {
            activity.getString(R.string.map_error_pack, pack)
        }
    }

    /**
     * The full reason, plus the two identifiers a support call needs.
     *
     * Not styled as a card: an `AlertDialog` on this ROM draws in the theme's
     * dialog surface, which is already the elevated navy the rest of the app
     * uses. It is dismissible with Back, which is what a driver will try.
     */
    private fun showDetailDialog(detail: String, snapshot: MapPhaseStore.Snapshot) {
        val body = buildString {
            append(detail)
            append("\n\n")
            append(
                activity.getString(
                    R.string.map_error_code,
                    snapshot.errorCode ?: MapPhaseStore.UNKNOWN_CODE
                )
            )
            append('\n')
            append(
                snapshot.packName?.let { activity.getString(R.string.map_error_pack, it) }
                    ?: activity.getString(R.string.map_error_pack_none)
            )
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.map_error_details_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

/**
 * Resolves a pack display name for the error surface without throwing.
 *
 * Used by a screen that failed before it could learn the pack's metadata: the
 * lookup itself is what failed, so asking it again inside the error handler is
 * the one call that must not be allowed to hang or throw. Null when the store
 * already has a better answer.
 */
internal fun Context.safePackName(graph: com.ts8.launcher.di.AppGraph): String? =
    runCatching { graph.pack()?.metadata?.name }.getOrNull()

/**
 * The stable code for a pack-load failure.
 *
 * **Why a code at all, when the sentence already says what happened.** §6
 * requires the failure surface to show an error code, and the reason is
 * practical: the sentence is Vietnamese and long, and the code is the one
 * thing a driver can read over the phone to someone who has seen this before.
 * The mapping is by content because [MapPack.LoadResult.Failure] carries a
 * reason, not a code — inventing a numeric enum for it in this batch would be
 * a change to the pack loader, which §2 puts out of scope.
 */
internal fun packErrorCode(reason: String): String {
    val lowered = reason.lowercase()
    return when {
        // The loader's own wording for "no directory in any configured root".
        "không tìm thấy" in lowered || "not found" in lowered ->
            MapPhaseStore.CODE_NO_PACK
        "quyền" in lowered || "permission" in lowered || "read" in lowered ->
            MapPhaseStore.CODE_PACK_UNREADABLE
        "style" in lowered || "kiểu" in lowered ->
            MapPhaseStore.CODE_STYLE_FAILED
        // Anything the renderer could not even construct: a missing GL driver,
        // a MapLibre native library that failed to load.
        "renderer" in lowered || "gl" in lowered || "maplibre" in lowered ->
            MapPhaseStore.CODE_RENDERER_UNAVAILABLE
        else -> MapPhaseStore.CODE_PACK_UNREADABLE
    }
}
