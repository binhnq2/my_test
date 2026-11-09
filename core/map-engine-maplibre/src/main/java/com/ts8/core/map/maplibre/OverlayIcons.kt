package com.ts8.core.map.maplibre

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Overlay icons drawn into bitmaps at runtime.
 *
 * **Why not a sprite sheet.** A MapLibre style normally names `sprite` — a PNG
 * atlas plus an index — and both are loaded by URL. Two hand-drawn icons are
 * not worth shipping an atlas for, and drawing them here means the overlay
 * works with no sprite file at all, which removes one more thing that can be
 * missing from a pack.
 *
 * Both icons are drawn on a transparent canvas at a size scaled by the display
 * density, because `icon-size: 1` in MapLibre means "the bitmap's own pixel
 * size", and an unscaled 32 px icon on a 1.5x panel looks like a toy.
 */
internal object OverlayIcons {

    /**
     * A car seen from above, nose pointing up.
     *
     * The symbol layer rotates it by the vehicle's compass bearing with
     * `icon-rotation-alignment: map`, so "up" on this bitmap is the direction
     * of travel. It is centre-anchored: the middle of the roof is the GPS fix,
     * which is also the centre of the halo circle under it. The old marker was
     * an arrow with a dot at 62 % of its height — a dot that read as the
     * position even though the real anchor was the bitmap centre. A car needs
     * no such cue, and keeping one would have put a white spot on its roof.
     */
    fun vehicle(density: Float): Bitmap {
        val size = (CAR_DP * density).toInt().coerceAtLeast(28)
        val s = size.toFloat()
        val cx = s / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val body = carBody(s)

        // A light drop shadow keeps the silhouette legible over both a bright
        // road casing and the near-black night background.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        canvas.save()
        canvas.translate(0.8f * density, 1.6f * density)
        canvas.drawPath(body, shadow)
        canvas.restore()

        // Wheels first: the body is drawn over their inner halves, so only the
        // part that sticks out past the sill remains. Four dark blocks at the
        // corners are what make a rounded rectangle read as a car at 40 px.
        val wheel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#142a47") }
        val wheelW = s * 0.10f
        val wheelH = s * 0.17f
        val wheelR = 2f * density
        for (y in floatArrayOf(s * 0.25f, s * 0.61f)) {
            canvas.drawRoundRect(
                RectF(cx - s * 0.28f, y, cx - s * 0.28f + wheelW, y + wheelH),
                wheelR, wheelR, wheel
            )
            canvas.drawRoundRect(
                RectF(cx + s * 0.18f, y, cx + s * 0.18f + wheelW, y + wheelH),
                wheelR, wheelR, wheel
            )
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1f7ae0") }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(body, fill)
        canvas.drawPath(body, stroke)

        // Windshield and rear window. Darker than the body so the glass reads
        // as an opening rather than a stripe painted on the roof.
        val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0f3f78") }
        canvas.drawPath(
            Path().apply {
                moveTo(cx - s * 0.13f, s * 0.27f)
                lineTo(cx + s * 0.13f, s * 0.27f)
                lineTo(cx + s * 0.17f, s * 0.42f)
                lineTo(cx - s * 0.17f, s * 0.42f)
                close()
            }, glass
        )
        canvas.drawPath(
            Path().apply {
                moveTo(cx - s * 0.16f, s * 0.62f)
                lineTo(cx + s * 0.16f, s * 0.62f)
                lineTo(cx + s * 0.13f, s * 0.74f)
                lineTo(cx - s * 0.13f, s * 0.74f)
                close()
            }, glass
        )

        // Headlights warm, tail lights red. At 40 px two pixels of colour on
        // the nose are enough to tell which way the car is facing even before
        // the bearing rotation is applied.
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#fff3b0") }
        val tail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ff6b5e") }
        for (side in floatArrayOf(-1f, 1f)) {
            canvas.drawCircle(cx + side * s * 0.14f, s * 0.165f, 1.9f * density, head)
            canvas.drawCircle(cx + side * s * 0.14f, s * 0.855f, 1.7f * density, tail)
        }
        return bmp
    }

    /**
     * A speed-limit sign: a red ring with the limit number in the centre.
     *
     * Drawn as a circle so it reads at the small sizes the overlay uses (28–32 px).
     * The number is painted at a size proportional to the ring, so "120" and
     * "40" are both centred and legible. The red ring matches the Vietnamese
     * P.127 sign (maximum speed limit).
     */
    fun speedLimitSign(density: Float, limitKmh: Int): Bitmap {
        val size = (SIGN_DP * density).toInt().coerceAtLeast(20)
        val s = size.toFloat()
        val cx = s / 2f
        val cy = s / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // White fill with a subtle shadow for depth over busy tiles.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
        canvas.drawCircle(cx + 0.8f * density, cy + 0.8f * density, s * 0.44f, shadow)

        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, cy, s * 0.42f, white)

        // Red ring — the universal "maximum" symbol.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#d32f2f")
            style = Paint.Style.STROKE
            strokeWidth = s * 0.07f
        }
        canvas.drawCircle(cx, cy, s * 0.38f, ring)

        // Speed number, centred. Font size scales with the sign size.
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1a1a1a")
            textAlign = Paint.Align.CENTER
            textSize = s * 0.34f
            isFakeBoldText = true
        }
        val text = limitKmh.toString()
        // Centred vertically: ascent/descent correction.
        val fm = textPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, textY, textPaint)
        return bmp
    }

    /**
     * A camera enforcement icon: a dark camera body with a red accent.
     *
     * Used for both speed cameras and red-light cameras; the red dot in the
     * lens distinguishes it from the speed-limit sign at a glance.
     */
    fun cameraSign(density: Float): Bitmap {
        val size = (SIGN_DP * density).toInt().coerceAtLeast(20)
        val s = size.toFloat()
        val cx = s / 2f
        val cy = s / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Shadow
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
        canvas.drawRoundRect(
            RectF(cx - s * 0.3f + 0.8f * density, cy - s * 0.22f + 0.8f * density,
                  cx + s * 0.3f + 0.8f * density, cy + s * 0.22f + 0.8f * density),
            s * 0.06f, s * 0.06f, shadow
        )

        // Camera body
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#37474f") }
        canvas.drawRoundRect(
            RectF(cx - s * 0.3f, cy - s * 0.22f, cx + s * 0.3f, cy + s * 0.22f),
            s * 0.06f, s * 0.06f, body
        )

        // Lens
        val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#d32f2f") }
        canvas.drawCircle(cx, cy, s * 0.13f, lens)
        val lensInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#b71c1c") }
        canvas.drawCircle(cx, cy, s * 0.08f, lensInner)

        // Top mount (the bracket that attaches the camera to the pole)
        val mount = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#263238") }
        canvas.drawRect(cx - s * 0.06f, cy - s * 0.30f, cx + s * 0.06f, cy - s * 0.22f, mount)
        return bmp
    }

    /**
     * A residential-area sign: a blue circle with a house silhouette.
     *
     * Used for R.420 (begin residential area) and R.421 (end). The blue disc
     * matches the Vietnamese warning sign colour for residential zones.
     */
    fun residentialSign(density: Float): Bitmap {
        val size = (SIGN_DP * density).toInt().coerceAtLeast(20)
        val s = size.toFloat()
        val cx = s / 2f
        val cy = s / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Shadow
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 0, 0, 0) }
        canvas.drawCircle(cx + 0.8f * density, cy + 0.8f * density, s * 0.42f, shadow)

        // Blue disc
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1565c0") }
        canvas.drawCircle(cx, cy, s * 0.40f, disc)

        // White house silhouette
        val house = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        // Roof (triangle)
        val roof = Path().apply {
            moveTo(cx, cy - s * 0.18f)
            lineTo(cx - s * 0.16f, cy - s * 0.02f)
            lineTo(cx + s * 0.16f, cy - s * 0.02f)
            close()
        }
        canvas.drawPath(roof, house)
        // Body (rectangle)
        canvas.drawRect(
            cx - s * 0.11f, cy - s * 0.02f, cx + s * 0.11f, cy + s * 0.14f, house
        )
        // Door
        val door = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1565c0") }
        canvas.drawRect(
            cx - s * 0.03f, cy + s * 0.04f, cx + s * 0.03f, cy + s * 0.14f, door
        )
        return bmp
    }

    /**
     * Returns the right icon image ID for a given sign type code.
     *
     * The caller registers the images once; this maps a sign's type to the
     * registered image name so a single symbol layer can pick the icon per
     * feature via `icon-image` expression.
     */
    fun iconIdForSignType(typeCode: String, speedLimit: Int): String {
        return when (typeCode) {
            "limit" -> "ts8-sign-limit-$speedLimit"
            "cam", "cam_speed", "cam_red" -> SIGN_CAMERA_IMAGE
            "r420", "r421" -> SIGN_RESIDENTIAL_IMAGE
            else -> SIGN_GENERIC_IMAGE
        }
    }

    /**
     * Registers all sign icon bitmaps needed for the given sign list onto a style.
     *
     * Takes [TrafficSignOverlay] (from `core:map-api`) rather than `TrafficSign`
     * (from `core:location-engine`) to avoid a module dependency: the map engine
     * must not depend on the location engine.
     *
     * **Why `registered` is a parameter rather than a query.** MapLibre 12's
     * `Style` has `addImage` but no `hasImage`/`getImage`, so the only way to
     * know what is already on the style is to remember it. The caller owns that
     * set because the style object is replaced on every rebuild (day/night,
     * layer toggle, pack change) and the set must be emptied at exactly the same
     * moment — see `MapLibreMapController.addOverlayLayers`. Re-registering an
     * image is not fatal (it overwrites), but it re-draws a bitmap per call,
     * which is the cost this set exists to avoid.
     *
     * @param registered IDs already added to this style. Newly added IDs are
     *   appended, so a later call in the same style generation skips them.
     */
    fun registerSignIcons(
        style: org.maplibre.android.maps.Style,
        density: Float,
        signs: List<com.ts8.core.map.TrafficSignOverlay>,
        registered: MutableSet<String>
    ) {
        // Always register the camera and residential icons.
        if (registered.add(SIGN_CAMERA_IMAGE)) {
            style.addImage(SIGN_CAMERA_IMAGE, cameraSign(density))
        }
        if (registered.add(SIGN_RESIDENTIAL_IMAGE)) {
            style.addImage(SIGN_RESIDENTIAL_IMAGE, residentialSign(density))
        }
        if (registered.add(SIGN_GENERIC_IMAGE)) {
            style.addImage(SIGN_GENERIC_IMAGE, residentialSign(density))
        }
        // Register speed-limit signs for each distinct limit value.
        val limits = signs.filter { it.speedLimit > 0 && it.typeCode == "limit" }
            .map { it.speedLimit }.toSet()
        for (limit in limits) {
            val id = "ts8-sign-limit-$limit"
            if (registered.add(id)) {
                style.addImage(id, speedLimitSign(density, limit))
            }
        }
    }

    /** A destination pin with a filled head and a point at the anchor. */
    fun pin(density: Float): Bitmap {
        val w = (PIN_W_DP * density).toInt().coerceAtLeast(16)
        val h = (PIN_H_DP * density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w / 2f
        val headR = w * 0.36f
        val headCy = headR + 2f * density

        val path = Path().apply {
            moveTo(cx, h.toFloat())
            lineTo(cx - headR * 0.62f, headCy + headR * 0.9f)
            lineTo(cx + headR * 0.62f, headCy + headR * 0.9f)
            close()
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e0392b") }
        canvas.drawPath(path, body)
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e0392b") }
        canvas.drawCircle(cx, headCy, headR, head)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, headCy, headR * 0.42f, inner)
        return bmp
    }

    /**
     * Rounded body, wider at the shoulder than at the nose. The two quads at
     * the front are what stop it looking like a capsule; the straight sides
     * between them leave room for the wheels.
     */
    private fun carBody(s: Float): Path {
        val cx = s / 2f
        return Path().apply {
            moveTo(cx - s * 0.22f, s * 0.88f)
            lineTo(cx - s * 0.24f, s * 0.34f)
            quadTo(cx - s * 0.24f, s * 0.14f, cx - s * 0.15f, s * 0.11f)
            lineTo(cx + s * 0.15f, s * 0.11f)
            quadTo(cx + s * 0.24f, s * 0.14f, cx + s * 0.24f, s * 0.34f)
            lineTo(cx + s * 0.22f, s * 0.88f)
            quadTo(cx + s * 0.22f, s * 0.92f, cx + s * 0.16f, s * 0.92f)
            lineTo(cx - s * 0.16f, s * 0.92f)
            quadTo(cx - s * 0.22f, s * 0.92f, cx - s * 0.22f, s * 0.88f)
            close()
        }
    }

    private const val CAR_DP = 40f
    private const val PIN_W_DP = 26f
    private const val PIN_H_DP = 38f
    private const val SIGN_DP = 28f

    /** Image IDs for traffic-sign overlay icons. */
    const val SIGN_CAMERA_IMAGE = "ts8-sign-camera"
    const val SIGN_RESIDENTIAL_IMAGE = "ts8-sign-residential"
    const val SIGN_GENERIC_IMAGE = "ts8-sign-generic"
}
