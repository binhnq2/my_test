package com.ts8.core.map.maplibre

import com.ts8.core.map.MapDisplayConfig
import com.ts8.core.map.MapStyleMode
import java.io.File

/**
 * The offline style document, generated rather than shipped as a static asset.
 *
 * **Why generate it.** A MapLibre style is a JSON document that must name the
 * tile source, the glyph root and every layer. Four of those values are pack
 * specific — the absolute path of `basemap.mbtiles`, the glyph directory, the
 * zoom range and the attribution — and a static asset cannot carry an absolute
 * path that differs per install. Shipping a style with a hardcoded
 * `/sdcard/ts8/...` is how offline maps end up blank on a device whose pack
 * lives somewhere else.
 *
 * **Why two documents rather than one with a theme switch.** A MapLibre style
 * cannot swap colours at runtime without re-parsing every layer; `setStyle`
 * rebuilds the style, which is a visible flash. Building both once and caching
 * them means a day/night toggle is a `setStyle` on an already-parsed document,
 * and the app can cross-fade instead of going white.
 *
 * Layer names match the tiles emitted by `tools/mapdata/build_map_pack.py`, so
 * the style and the pack cannot drift apart silently: a missing layer renders as
 * nothing, and the diagnostics screen compares the two lists.
 */
object StyleBuilder {

    /** Tileset name the builder writes into the MBTiles metadata. */
    private const val SOURCE_ID = "ts8"

    /**
     * Builds a style for [pack] in the given [mode].
     *
     * @param devicePixelRatio the display density, because text size is in
     *        pixels and a 1.5x screen would otherwise draw labels at 2/3 size.
     * @param display the driver's `Hiển thị` choices. Every field is read here
     *        — layer presence, minimum zooms, label sizes and route colour all
     *        come from it — because a setting the renderer ignores is a switch
     *        that does nothing, which §8 forbids outright.
     * @param glyphsDirOverride when non-null, overrides the pack's own glyph
     *        directory. Used to inject APK-bundled fallback fonts when the
     *        pack has none — without this, a pack missing `fonts/` causes
     *        MapLibre to hang in style loading forever.
     */
    fun build(
        pack: MapPack,
        mode: MapStyleMode,
        devicePixelRatio: Float = 1f,
        display: MapDisplayConfig = MapDisplayConfig(),
        glyphsDirOverride: File? = null
    ): String {
        val night = mode == MapStyleMode.NIGHT
        val palette = if (night) Palette.NIGHT else Palette.DAY
        val zoom = pack.metadata

        val json = org.json.JSONObject()
        json.put("version", 8)
        json.put("name", "TS8 ${pack.metadata.name} ${if (night) "Đêm" else "Ngày"}")
        json.put("center", pack.center?.let {
            org.json.JSONArray().put(it.lon).put(it.lat)
        } ?: org.json.JSONArray().put(108.2215).put(16.0678))
        json.put("zoom", zoom.defaultZoom)
        json.put("bearing", 0)
        // A style that starts tilted makes the first frame of a 2D-only map
        // wrong until the camera is driven; the tilt a driver asked for is
        // applied by the navigation camera, not baked into the document.
        json.put("pitch", 0)
        json.put("light", buildLight(night))

        // Glyphs and sprite must point at files on disk.
        //
        // **The scheme is not optional.** MapLibre LocalFileSource accepts only
        // `file://` URLs (lfs.cpp, acceptsURL) and refuses everything else with
        // "Invalid file URL", after which the request falls through every other
        // source and ends as "Unsupported resource request." — so a bare
        // `/data/.../fonts/...` path renders geometry with no labels at all, and
        // nothing anywhere reports why. PackPaths.fileUrl adds the scheme and
        // encodes the path exactly once; `{fontstack}`/`{range}` stay literal
        // because MapLibre substitutes them before the URL is fetched.
        //
        // **The fallback matters.** When the pack has no `fonts/` directory,
        // [glyphsDirOverride] supplies the APK-bundled fallback extracted by
        // [GlyphFallback]. Without it the style omits `glyphs` entirely, and
        // MapLibre hangs on any symbol layer that has a `text-field` — the
        // exact "Đang tải kiểu hiển thị" symptom on real hardware.
        val glyphsDir = glyphsDirOverride ?: pack.resolveGlyphsDir()
        glyphsDir?.let { dir ->
            json.put("glyphs", PackPaths.glyphsTemplate(dir))
        }

        // A sprite is honoured only when both halves exist. MapLibre drops
        // every icon that references a sprite whose JSON 404s, which would
        // remove the pin and vehicle icons this engine registers itself — worse
        // than having no sprite block.
        pack.resolveSprite()?.let { base ->
            json.put("sprite", PackPaths.fileUrl(base))
        }

        json.put("sources", buildSources(pack))
        json.put(
            "layers",
            buildLayers(pack, night, palette, zoom.minZoom, zoom.maxZoom, devicePixelRatio, display)
        )

        return json.toString()
    }

    private fun buildLight(night: Boolean): org.json.JSONObject {
        // fill-extrusion needs an explicit light or MapLibre shades every
        // building identically and the 3D view reads as flat cards.
        val light = org.json.JSONObject()
        light.put("anchor", "viewport")
        light.put("color", if (night) "#ffd7a8" else "#ffffff")
        light.put("intensity", if (night) 0.35 else 0.45)
        val position = org.json.JSONArray().put(1.15).put(if (night) 210.0 else 90.0).put(30.0)
        light.put("position", position)
        return light
    }

    private fun buildSources(pack: MapPack): org.json.JSONObject {
        val sources = org.json.JSONObject()
        val source = org.json.JSONObject()
        source.put("type", "vector")
        source.put("tiles", org.json.JSONArray().put(pack.tilesUri))
        source.put("minzoom", pack.metadata.minZoom)
        source.put("maxzoom", pack.metadata.maxZoom)
        pack.bounds?.let { b ->
            source.put("bounds", org.json.JSONArray().put(b.west).put(b.south).put(b.east).put(b.north))
        }
        pack.metadata.attribution?.let { source.put("attribution", it) }
        sources.put(SOURCE_ID, source)

        // **No overlay sources here.** The route, vehicle and destination
        // GeoJSON sources are added by `MapLibreMapController.addOverlayLayers`
        // after the style loads, because they change at runtime and rewriting
        // the style document for each would rebuild the whole style.
        //
        // Declaring them here as well used to crash the overlays outright:
        // MapLibre's native `addSource` rejects a duplicate id with
        // "Cannot add source twice" (CannotAddSourceException), the controller's
        // `addSource` for the route then threw on its first line, and the catch
        // that guards the overlay block swallowed it — so the vehicle marker and
        // the destination pin were never added on any map, with nothing on
        // screen to say why. One owner per source id.
        return sources
    }

    /**
     * Every layer the style contains, in draw order.
     *
     * **Why the display config is a parameter rather than read from a field.**
     * The builder is shared between the initial load and every settings change,
     * and a field would make the document depend on state that changed between
     * the caller reading the pack and the builder writing the JSON. Everything
     * the document needs arrives in one immutable snapshot.
     *
     * **Why the route layers are gone from here.** They live in the controller,
     * which owns the `ts8-route` source at runtime. See [buildSources].
     */
    private fun buildLayers(
        pack: MapPack,
        night: Boolean,
        p: Palette,
        minZoom: Double,
        maxZoom: Double,
        dpr: Float,
        display: MapDisplayConfig
    ): org.json.JSONArray {
        val layers = org.json.JSONArray()
        fun add(layer: org.json.JSONObject) { layers.put(layer) }
        val scale = display.textMultiplier

        val hasGlyphs = pack.hasGlyphs

        add(background(p.background))

        // Minimum zooms mirror `LAYER_MIN_ZOOM` in tools/mapdata/build_map_pack.py.
        // Setting a style layer below the zoom at which the pack starts emitting
        // it draws nothing for several zoom levels and costs a tile fetch per
        // level, so the two lists are kept in step deliberately.
        add(fillLayer("water", SOURCE_ID, "water", p.water, 6.0))
        // Widths are pixels at the top of each layer's zoom ramp, not a
        // multiplier: 8 px is a river several hundred metres wide at z15, and
        // 800 px was a slab covering the whole panel (see `lineLayer`).
        add(lineLayer("waterway", SOURCE_ID, "waterway", p.waterLine, 8f, 9.0))

        add(fillLayer("landuse", SOURCE_ID, "landuse", p.landuse, 8.0))
        add(fillLayer("landcover", SOURCE_ID, "landcover", p.landcover, 9.0))

        // Administrative boundaries. The pack emits `boundary` from zoom 6
        // (`LAYER_MIN_ZOOM`) and 2 829 of those features at z14 alone, but no
        // style layer ever read them, so province and ward lines simply were not
        // on the map. Dashed, because a solid line of the same weight as a road
        // reads as a road.
        add(lineLayer("boundary", SOURCE_ID, "boundary", p.boundary, 1.5f, 6.0, dashed = true))

        // Buildings. The flat footprint layer is always present from zoom 12 —
        // that is where the pack starts emitting the layer, and a driver looking
        // at a city block needs to see the block whether or not extrusion is on.
        add(fillLayer("building-2d", SOURCE_ID, "building", p.building2d, 12.0))
        // Extrusion only when the driver asked for a view that has any, and only
        // at the zoom where it reads as a skyline rather than as spikes.
        if (display.wantsExtrusion) {
            add(building3dLayer(p, display.extrusionMinZoom))
        }

        // Quality: `SAVER` drops the thin-line layers. Each of these is a real
        // reduction in drawn geometry, not a relabelled constant.
        val saver = display.performance == MapDisplayConfig.Performance.SAVER

        // **The three road layers need filters, or they draw the same road three
        // times.** The pack emits one `road` source-layer and distinguishes the
        // highway class with the numeric `rank` property (0 motorway … 7
        // footway, see `ROAD_CLASS` in tools/mapdata/build_map_pack.py). Without
        // a filter all three layers paint every road in the pack, in draw order,
        // so `road-motorway` — the last and widest — covered the whole network
        // in orange and the map lost every trace of road hierarchy.
        //
        // `rank` 7 is deliberately not drawn: footway, path, cycleway, steps and
        // pedestrian are not roads a car can take, and painting them at the same
        // width and colour as a residential street reads as drivable.
        add(
            lineLayer("road-minor", SOURCE_ID, "road", p.roadMinor, 1f, 6.0,
                widthExpr = roadMinorWidth, filter = rankBetween(5, 6))
        )
        add(
            lineLayer("road-major", SOURCE_ID, "road", p.roadMajor, 2f, 6.0,
                widthExpr = roadMajorWidth, filter = rankBetween(2, 4))
        )
        add(
            lineLayer("road-motorway", SOURCE_ID, "road", p.roadMotorway, 3f, 6.0,
                widthExpr = roadMotorwayWidth, filter = rankAtMost(1))
        )

        if (!saver) {
            add(lineLayer("railway", SOURCE_ID, "railway", p.railway, 5f, 8.0, dashed = true))
            add(lineLayer("aeroway", SOURCE_ID, "aeroway", p.aeroway, 6f, 9.0))
        }

        // Bus stops and platforms. The pack writes them into a `transportation`
        // layer from zoom 13 and nothing drew them; a 3 px dot is enough to show
        // that a stop exists without competing with the road it sits on.
        add(
            circleLayer("transport-stop", SOURCE_ID, "transportation", p.transportStop, 3.5f, 13.0)
        )

        if (display.labelPlace) {
            add(
                symbolLayer("place-label", SOURCE_ID, "place", p.text, p.textHalo, dpr,
                    minZoom = 6.0, size = placeSize, bold = true, scale = scale,
                    hasGlyphs = hasGlyphs, sortByRank = true)
            )
        }
        if (display.labelRoads) {
            add(
                symbolLayer("road-label", SOURCE_ID, "road", p.textRoad, p.textHalo, dpr,
                    minZoom = 12.0, size = roadSize, bold = false, scale = scale,
                    hasGlyphs = hasGlyphs, sortByRank = true)
            )
        }
        if (display.labelPoi) {
            add(
                symbolLayer("poi-label", SOURCE_ID, "poi", p.text, p.textHalo, dpr,
                    minZoom = display.poiMinZoom, size = poiSize, bold = false, scale = scale,
                    hasGlyphs = hasGlyphs, sortByRank = true)
            )
        }
        if (display.labelHouseNumbers) {
            add(houseNumberLayer(p, dpr, scale, hasGlyphs = hasGlyphs))
        }

        return layers
    }

    // -- layer construction -------------------------------------------------

    private fun background(color: String): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("id", "background")
            put("type", "background")
            put("paint", org.json.JSONObject().put("background-color", color))
        }

    private fun fillLayer(
        id: String,
        source: String,
        sourceLayer: String?,
        color: String,
        minZoom: Double
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("type", "fill")
        if (sourceLayer == null) {
            put("source", source)
        } else {
            put("source", source)
            put("source-layer", sourceLayer)
        }
        put("minzoom", minZoom)
        put("paint", org.json.JSONObject().put("fill-color", color))
    }

    /**
     * One line layer.
     *
     * @param width the line's width in pixels at the top of its zoom ramp; only
     *        used when [widthExpr] is null. It is a pixel count, not a
     *        multiplier — a value of 800 draws an 800 px slab, not a 8 px line.
     * @param filter optional `filter` expression. Required whenever more than
     *        one layer reads the same source-layer, which is the case for the
     *        three road layers: without it each of them draws every feature.
     */
    private fun lineLayer(
        id: String,
        source: String,
        sourceLayer: String?,
        color: String,
        width: Float,
        minZoom: Double,
        dashed: Boolean = false,
        widthExpr: org.json.JSONArray? = null,
        filter: org.json.JSONArray? = null
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("type", "line")
        put("source", source)
        if (sourceLayer != null) put("source-layer", sourceLayer)
        if (filter != null) put("filter", filter)
        put("minzoom", minZoom)
        val paint = org.json.JSONObject()
        paint.put("line-color", color)
        // Width interpolates with zoom so a street is a hairline at city scale
        // and a real road at street scale, which is what makes the map read as
        // a map instead of a diagram of spikes.
        if (widthExpr != null) {
            paint.put("line-width", widthExpr)
        } else {
            paint.put(
                "line-width",
                interpolateZoom(0.5, minZoom, width.toDouble(), maxOf(minZoom + 6, 15.0))
            )
        }
        if (dashed) {
            val layout = org.json.JSONObject()
            layout.put("line-cap", "butt")
            put("layout", layout)
            paint.put("line-dasharray", org.json.JSONArray().put(2).put(2))
        }
        put("paint", paint)
    }

    /**
     * A filled dot, used for the point layers that are not labels.
     *
     * Kept separate from [fillLayer] because a circle layer takes a `radius` in
     * pixels and ignores polygon geometry — the pack's `transportation` layer is
     * points, so a fill layer would draw nothing at all.
     */
    private fun circleLayer(
        id: String,
        source: String,
        sourceLayer: String,
        color: String,
        radius: Float,
        minZoom: Double
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("type", "circle")
        put("source", source)
        put("source-layer", sourceLayer)
        put("minzoom", minZoom)
        put("paint", org.json.JSONObject().apply {
            put("circle-color", color)
            put("circle-radius", radius.toDouble())
        })
    }

    /**
     * The pack's numeric highway rank, defaulting to 7 ("footway") when a
     * feature carries none.
     *
     * The default matters: a pack built before `rank` existed has no value to
     * read, and `coalesce` keeps such a road in the thinnest layer instead of
     * letting a comparison against null silently hide the whole network.
     */
    private fun rankValue(): org.json.JSONArray =
        org.json.JSONArray().put("coalesce")
            .put(org.json.JSONArray().put("get").put("rank"))
            .put(7)

    private fun rankAtMost(max: Int): org.json.JSONArray =
        org.json.JSONArray().put("<=").put(rankValue()).put(max)

    private fun rankBetween(min: Int, max: Int): org.json.JSONArray =
        org.json.JSONArray().put("all")
            .put(org.json.JSONArray().put(">=").put(rankValue()).put(min))
            .put(org.json.JSONArray().put("<=").put(rankValue()).put(max))

    /**
     * Buildings extruded from the tag-derived height.
     *
     * `fill-extrusion-height` reads `render_height`, which the pack builder
     * computes from `height`, then `building:levels × 3.2`, then a per-type
     * default. Using the tag rather than a constant is what makes the 3D view
     * describe the actual skyline.
     *
     * @param minZoom from [MapDisplayConfig.extrusionMinZoom]; a 3D mode shows
     *        the skyline earlier than the automatic one, which only extrudes
     *        once the street network is legible.
     */
    private fun building3dLayer(p: Palette, minZoom: Double): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("id", "building-3d")
            put("type", "fill-extrusion")
            put("source", SOURCE_ID)
            put("source-layer", "building")
            put("minzoom", minZoom)
            put("paint", org.json.JSONObject().apply {
                put("fill-extrusion-color", p.building3d)
                put("fill-extrusion-height", heightExpression())
                put("fill-extrusion-base", 0)
                put("fill-extrusion-opacity", 0.85)
            })
        }

    /**
     * House-number labels, emitted only when the driver turns them on.
     *
     * Reads `housenumber` from a `housenumber` source-layer. The current pack
     * builder does not emit that layer, so this draws nothing until a pack
     * carries the tag; MapLibre treats an absent `source-layer` as an empty
     * layer, not as an error, which is why it is safe to include.
     */
    private fun houseNumberLayer(p: Palette, dpr: Float, scale: Float, hasGlyphs: Boolean = true): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("id", "housenumber-label")
            put("type", "symbol")
            put("source", SOURCE_ID)
            put("source-layer", "housenumber")
            put("minzoom", 17.0)
            put("filter", org.json.JSONArray().put("!=")
                .put(org.json.JSONArray().put("get").put("housenumber")).put(""))
            put("layout", org.json.JSONObject().apply {
                put("text-field", org.json.JSONArray().put("get").put("housenumber"))
                if (hasGlyphs) {
                    put("text-font", org.json.JSONArray().put(FAMILY_REGULAR))
                }
                put("text-size", scaled(10.0, scale))
                put("text-allow-overlap", false)
            })
            put("paint", org.json.JSONObject().apply {
                put("text-color", p.text)
                put("text-halo-color", p.textHalo)
                put("text-halo-width", 1.2f * dpr.coerceIn(1f, 2f))
            })
        }

    /**
     * Height with a graceful ladder: every fallback is a real tag present in
     * the pack before it becomes a constant.
     *
     * The pack builder (`build_map_pack.py`, `building_height`) writes `height`
     * in metres and `levels` as `height / 3.0` — both are emitted, so the
     * expression prefers the metres the builder already resolved rather than
     * re-deriving anything. Only buildings with neither tag fall through to the
     * class default, and `building=yes` with no height data is the common case
     * in Vietnamese OSM data.
     */
    private fun heightExpression(): org.json.JSONArray =
        org.json.JSONArray().apply {
            put("coalesce")
            put(org.json.JSONArray().put("get").put("height"))
            // `levels` is height/3 per the builder, so multiplying back by 3.2
            // would over-shoot; 3.0 reproduces the builder's own figure.
            put(org.json.JSONArray().put("*").put(org.json.JSONArray().put("get").put("levels")).put(3.0))
            put(org.json.JSONArray().put("match")
                .put(org.json.JSONArray().put("get").put("class"))
                .put(org.json.JSONArray().put("apartments").put("office").put("hotel").put("commercial"))
                .put(12)
                .put(org.json.JSONArray().put("house").put("residential").put("yes"))
                .put(7)
                .put(8))
        }

    private fun symbolLayer(
        id: String,
        source: String,
        sourceLayer: String,
        color: String,
        halo: String,
        dpr: Float,
        minZoom: Double,
        size: org.json.JSONArray,
        bold: Boolean,
        scale: Float = 1f,
        hasGlyphs: Boolean = true,
        sortByRank: Boolean = false
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", id)
        put("type", "symbol")
        put("source", source)
        put("source-layer", sourceLayer)
        put("minzoom", minZoom)
        // A label with no text field throws in MapLibre ("text-field requires"),
        // and the pack writes `name: ""` for unnamed streets. Filtering on a
        // non-empty name is what stops an unnamed service road crashing the
        // style load on a real tile.
        put("filter", org.json.JSONArray().put("!=").put(org.json.JSONArray().put("get").put("name")).put(""))
        put("layout", org.json.JSONObject().apply {
            put("text-field", org.json.JSONArray().put("get").put("name"))
            // The pack writes a `rank` on every place and POI precisely so the
            // renderer can decide which label wins a collision — 1 is a city or
            // a hospital, 6 is a shop. Nothing read it before, so a corner shop
            // could take the slot a hospital needed. Ascending order puts the
            // low ranks down first, and MapLibre places earlier symbols first.
            if (sortByRank) {
                put("symbol-sort-key", org.json.JSONArray().put("get").put("rank"))
            }
            if (hasGlyphs) {
                put("text-font", org.json.JSONArray().put(if (bold) FAMILY_BOLD else FAMILY_REGULAR))
            }
            put("text-size", scaled(size, scale))
            put("text-max-width", 10)
            put("text-padding", 4)
            put("text-allow-overlap", false)
            put("text-ignore-placement", false)
            // Rotate street labels with the road, but keep place names upright.
            if (sourceLayer == "road") {
                put("symbol-placement", "line")
                put("text-rotation-alignment", "map")
                put("symbol-spacing", 300)
            }
        })
        put("paint", org.json.JSONObject().apply {
            put("text-color", color)
            // A halo is what makes a label readable over a busy background at
            // arm's length in daylight; without it labels vanish into the map.
            put("text-halo-color", halo)
            put("text-halo-width", 1.4f * dpr.coerceIn(1f, 2f))
        })
    }

    /**
     * Multiplies every stop of a zoom-interpolated size expression by [scale].
     *
     * Size expressions are `["interpolate", ["linear"], ["zoom"], z0, s0, z1,
     * s1, …]`, so after the three preamble entries the **odd** indices are the
     * zoom stops and the **even** ones are the sizes. Only the sizes may be
     * scaled: touching the zoom stops makes labels appear at a different zoom
     * instead of at a different size, which is a different setting — and since
     * the stops must ascend, scaling them also produced an invalid expression
     * for `LARGE` rather than a bigger label.
     */
    private fun scaled(size: org.json.JSONArray, scale: Float): org.json.JSONArray {
        if (scale == 1f) return size
        val out = org.json.JSONArray()
        for (i in 0 until size.length()) {
            val value = size.get(i)
            if (i >= 4 && i % 2 == 0 && value is Number) {
                out.put(value.toDouble() * scale)
            } else {
                out.put(value)
            }
        }
        return out
    }

    /** Single-value equivalent of [scaled] for a plain number. */
    private fun scaled(value: Double, scale: Float): Double = value * scale

    private fun interpolateZoom(from: Double, fromZoom: Double, to: Double, toZoom: Double): org.json.JSONArray =
        org.json.JSONArray().apply {
            put("interpolate")
            put(org.json.JSONArray().put("linear"))
            put(org.json.JSONArray().put("zoom"))
            put(fromZoom); put(from)
            put(toZoom); put(to)
        }

    private val roadMinorWidth = interpolateZoom(1.2, 8.0, 7.0, 16.0)
    private val roadMajorWidth = interpolateZoom(2.5, 8.0, 14.0, 16.0)
    private val roadMotorwayWidth = interpolateZoom(4.0, 6.0, 20.0, 16.0)

    private val placeSize = org.json.JSONArray().apply {
        put("interpolate"); put(org.json.JSONArray().put("linear")); put(org.json.JSONArray().put("zoom"))
        put(6); put(11); put(10); put(13); put(14); put(16)
    }
    private val roadSize = org.json.JSONArray().apply {
        put("interpolate"); put(org.json.JSONArray().put("linear")); put(org.json.JSONArray().put("zoom"))
        put(13); put(10); put(16); put(13)
    }
    private val poiSize = org.json.JSONArray().apply {
        put("interpolate"); put(org.json.JSONArray().put("linear")); put(org.json.JSONArray().put("zoom"))
        put(14); put(10); put(18); put(13)
    }

    /** Zoom from which buildings are extruded, per the spec. */
    const val THREE_D_MIN_ZOOM = 15.0

    /**
     * Glyph family names, used both as the pack's `fonts/` directory names and
     * as the style's `text-font` value.
     *
     * These must match the directories `tools/mapdata/download_fonts.py`
     * creates. The name recorded *inside* a glyph file is not used: MapLibre
     * resolves a fontstack by the directory the URL template names, and the
     * demo font endpoint happens to write a long comma-joined fallback chain
     * into that field, which MapLibre ignores.
     */
    private const val FAMILY_BOLD = "Noto Sans Bold"
    private const val FAMILY_REGULAR = "Noto Sans Regular"


    /** GeoJSON source id the route overlay draws into. */
    const val ROUTE_SOURCE = "ts8-route"

    /** Base source id, so the diagnostics screen can compare against the pack. */
    const val BASE_SOURCE = SOURCE_ID

    private class Palette(
        val background: String,
        val water: String,
        val waterLine: String,
        val landuse: String,
        val landcover: String,
        val boundary: String,
        val transportStop: String,
        val building2d: String,
        val building3d: String,
        val roadMinor: String,
        val roadMajor: String,
        val roadMotorway: String,
        val railway: String,
        val aeroway: String,
        val text: String,
        val textRoad: String,
        val textHalo: String,
        val route: String,
        val routeCasing: String
    ) {
        companion object {
            /**
             * Day colours aimed at an OLED in sunlight: high contrast between
             * the road casings and the land, because the driver reads the road
             * shape in a glance and has no time for subtlety.
             */
            val DAY = Palette(
                background = "#f4f1ea",
                water = "#99c4eb",
                waterLine = "#5092d6",
                landuse = "#dbebd4",
                landcover = "#d2e6c8",
                boundary = "#8d99ab",
                transportStop = "#3b6fb6",
                building2d = "#e5dfd5",
                building3d = "#d6ccc0",
                roadMinor = "#ffffff",
                roadMajor = "#ffcb52",
                roadMotorway = "#ff9b36",
                railway = "#78808a",
                aeroway = "#d0d4db",
                text = "#1f242b",
                textRoad = "#2c323b",
                textHalo = "#ffffff",
                route = "#1a73e8",
                routeCasing = "#ffffff"
            )

            /**
             * Night colours for an OLED: the background is near-black so the
             * panel draws almost no light in a dark car, and the route stays
             * saturated because it is the one thing being followed.
             */
            val NIGHT = Palette(
                background = "#1d2c3a",
                water = "#15202b",
                waterLine = "#223547",
                landuse = "#223344",
                landcover = "#1e2e3e",
                boundary = "#4a6076",
                transportStop = "#6fa8dc",
                building2d = "#28394a",
                building3d = "#32465b",
                roadMinor = "#54687a",
                roadMajor = "#f5ac38",
                roadMotorway = "#ff8c2b",
                railway = "#485b6e",
                aeroway = "#384a5c",
                text = "#f0f4f8",
                textRoad = "#e1e8f0",
                textHalo = "#15202b",
                route = "#388eff",
                routeCasing = "#15202b"
            )
        }
    }
}
