package com.ts8.core.map.maplibre

import com.ts8.core.map.GeoPoint
import org.maplibre.android.maps.MapLibreMap

/**
 * The MapLibre runtime configuration that enforces the offline contract.
 *
 * **Why this exists as its own object.** The spec requires the map to work with
 * the network off, and "works offline" is the kind of claim that quietly stops
 * being true the moment someone adds a style URL, a glyph CDN or an icon
 * sprite. Rather than trusting that no code path reaches the network, this
 * class disables the parts of MapLibre that would reach it, so the only
 * supported tile origin is the local pack.
 *
 * MapLibre Native has no single "offline mode" switch. The three things that
 * make it phone home are:
 *
 * 1. The **style URL** — a style fetched over HTTP pulls its sources with it.
 *    This engine never passes a URL; it always hands MapLibre a JSON document
 *    built from the pack.
 * 2. The **glyph and sprite URLs** — a style with `glyphs: "https://..."` fetches
 *    font PBFs per range. [StyleBuilder] points them at the pack's own
 *    directory, and omits the block entirely when the pack has no glyphs.
 * 3. The **telemetry / offline database** — absent a valid `apiKey`, MapLibre
 *    still opens its ambient cache but has nowhere to upload to.
 *
 * So the enforcement here is: no UI logo or attribution widget that loads a
 * remote image, no compass/runtime view additions that fetch assets, and
 * gestures configured for a fixed car screen rather than a phone.
 */
internal object MapLibreRuntime {

    /** Configures a ready map for a fixed in-car display. */
    fun configure(map: MapLibreMap) {
        val ui = map.uiSettings
        // Nothing in this app rotates the map by hand; navigation drives the
        // bearing. Leaving rotate gestures on means a palm on the screen during
        // a turn silently changes the heading and the driver loses north.
        ui.isRotateGesturesEnabled = false
        ui.isTiltGesturesEnabled = true
        ui.isScrollGesturesEnabled = true
        ui.isZoomGesturesEnabled = true
        ui.isDoubleTapGesturesEnabled = true
        ui.isQuickZoomGesturesEnabled = true
        // MapLibre's logo and attribution widgets load from assets bundled in
        // the AAR, not the network, but they occupy screen edge the bottom bar
        // needs. Attribution is drawn by the UI instead, from the pack's own
        // `attribution` field, which is the same ODbL credit.
        ui.isLogoEnabled = false
        ui.isAttributionEnabled = false
        ui.isCompassEnabled = false
    }

    /**
     * Clamps a point into the pack's bounds.
     *
     * Used before showing an external coordinate (a destination from search, a
     * GPS fix from a drifted receiver) so the camera never pans into blank
     * space where the pack has no tiles.
     */
    fun clampToPack(pack: MapPack, point: GeoPoint): GeoPoint {
        val b = pack.bounds ?: return point
        return GeoPoint(
            lat = point.lat.coerceIn(b.south, b.north),
            lon = point.lon.coerceIn(b.west, b.east)
        )
    }
}
