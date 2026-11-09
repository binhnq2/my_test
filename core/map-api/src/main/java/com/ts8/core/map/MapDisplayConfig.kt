package com.ts8.core.map

/**
 * Everything the `Hiển thị` panel can change about how the map is drawn.
 *
 * **Why this is a value object and not a callback into the settings store.**
 * `core:map-api` has no Android context and must not learn what a
 * `SharedPreferences` is. The launcher reads the driver's choices and hands
 * down one immutable snapshot; the renderer compares snapshots by equality to
 * decide whether a rebuild is needed. Without an equality-based snapshot the
 * common case — re-applying the stored settings on every `onResume` — would
 * rebuild the style and blank the map each time the driver switches tabs.
 *
 * **Why the defaults match §8 exactly.** A renderer constructed before the
 * driver ever opened the panel has to draw something, and the something it
 * draws must be what the brief calls the default. If the default lived in two
 * places (here and in the settings store) they would drift, and the panel would
 * open showing `Cân bằng` while the map was running on something else.
 *
 * **Why every field is honoured by the style builder.** The brief forbids a
 * switch that stores a value and changes nothing. Each property below is read
 * in [com.ts8.core.map.maplibre.StyleBuilder] to add, drop or resize a layer,
 * and [MapController.applyDisplayConfig] rebuilds the style when the snapshot
 * changes.
 */
data class MapDisplayConfig(
    /** 2D, 3D, or 3D only once zoomed in far enough to be readable. */
    val mode: MapMode = MapMode.FLAT_2D,
    /** Extruded buildings at all. Off means flat footprints only. */
    val buildings3d: Boolean = true,
    val labelRoads: Boolean = true,
    val labelPoi: Boolean = true,
    val labelPlace: Boolean = true,
    /**
     * House numbers.
     *
     * The shipped pack format does **not** emit `addr:housenumber` today, so
     * this layer exists and renders nothing until a pack carries the tag. That
     * is deliberate: the alternative is a switch that lies about working.
     * `docs/MAP_MANAGEMENT.md` records the limitation.
     */
    val labelHouseNumbers: Boolean = false,
    val poiDensity: PoiDensity = PoiDensity.NORMAL,
    val textScale: TextScale = TextScale.NORMAL,
    /** ARGB of the active route line. */
    val routeColour: Int = 0xFF3B82F6.toInt(),
    /** ARGB of an alternative route. */
    val altRouteColour: Int = 0xFF6C7A91.toInt(),
    /**
     * §8's "Hiển thị route đã đi và route còn lại".
     *
     * Off erases the driven part and leaves only what is ahead; on keeps it
     * drawn in a dimmer shade. This is a rendering choice, so it lives in the
     * display snapshot rather than beside the other route colours: it changes
     * which layers exist, not just what colour they are.
     */
    val showTravelledRoute: Boolean = true,
    val performance: Performance = Performance.BALANCED
) {
    enum class MapMode {
        /** Always tilted flat; the cheapest to draw. */
        FLAT_2D,

        /** Extruded buildings from the moment the pack emits them. */
        THREE_D,

        /** Flat while zoomed out, 3D once the streets are readable. */
        AUTO
    }

    enum class PoiDensity {
        /** Only the important POIs, and only when well zoomed in. */
        LOW,
        NORMAL,
        /** Every POI the pack carries, from the zoom it first appears at. */
        HIGH
    }

    enum class TextScale {
        SMALL,
        NORMAL,
        LARGE
    }

    /**
     * How much of the GPU and the tile pipeline the map may use.
     *
     * The three names are the brief's, in the brief's order, with `BALANCED` as
     * the default because this panel has to stay smooth while navigating on a
     * device with a weak GPU.
     */
    enum class Performance {
        QUALITY,
        BALANCED,
        SAVER
    }

    /** Multiplier applied to every label and icon size in the style. */
    val textMultiplier: Float
        get() = when (textScale) {
            TextScale.SMALL -> 0.85f
            TextScale.NORMAL -> 1f
            TextScale.LARGE -> 1.25f
        }

    /** True when the style should contain an extruded-building layer. */
    val wantsExtrusion: Boolean
        get() = buildings3d && mode != MapMode.FLAT_2D

    /** First zoom at which 3D buildings may appear. */
    val extrusionMinZoom: Double
        get() = when (mode) {
            MapMode.THREE_D -> 13.0
            // AUTO keeps the spec's threshold: below it the extrusion hides the
            // street network the driver is actually using.
            MapMode.AUTO -> 15.0
            MapMode.FLAT_2D -> Double.MAX_VALUE
        }

    /** First zoom at which POI labels may appear. */
    val poiMinZoom: Double
        get() = when (poiDensity) {
            PoiDensity.LOW -> 15.0
            PoiDensity.NORMAL -> 12.0
            PoiDensity.HIGH -> 11.0
        }
}
