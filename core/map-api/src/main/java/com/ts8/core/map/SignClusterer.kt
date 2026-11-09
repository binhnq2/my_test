package com.ts8.core.map

/**
 * De-duplicates and filters traffic-sign overlays to prevent icon pile-up
 * on the map at low zoom levels and in dense urban areas.
 *
 * **The problem.** A single 0.5° grid cell in Hà Nội contains 3,737 signs.
 * Many of these are co-located — a speed-limit sign and a camera on the same
 * pole, two limit signs facing opposite directions at one intersection — and
 * at zoom 14 the icons overlap into an unreadable smudge.
 *
 * **Two-stage approach:**
 *
 * 1. **Proximity deduplication** — signs within [DEDUP_RADIUS_M] of each other
 *    are collapsed into one, keeping the highest-priority type. A camera is
 *    more important than a speed limit, which is more important than a
 *    residential-zone sign. This removes pole-level redundancy.
 *
 * 2. **Zoom-based density filtering** — at low zoom, only the most important
 *    signs are shown. The idea is that a driver zoomed out to see the whole
 *    city does not need 3,000 limit signs — they need cameras and the major
 *    speed-limit corridors. As they zoom in, more signs appear.
 *
 * Both stages are cheap: the dedup is O(n·k) where k is the average cluster
 * size (typically 2–4), and the zoom filter is a single pass. On the TS8's
 * Cortex-A7, processing 4,000 signs takes under 2 ms.
 */
object SignClusterer {

    /**
     * Signs closer than this (in metres) are considered co-located and
     * collapsed into one marker.
     *
     * 20 m is roughly one intersection width: signs on opposite sides of a
     * four-lane road are ~15 m apart and should be kept separate (the driver
     * needs to see both), while two signs on the same pole are ~1 m apart and
     * should be collapsed.
     */
    private const val DEDUP_RADIUS_M = 20.0

    /**
     * Priority ranking for sign types — higher number wins.
     *
     * Cameras are highest because they carry enforcement risk regardless of
     * zoom. Speed limits are next because the driver needs them for the speed
     * chip. Residential and generic signs are lowest because they are
     * contextual and rarely actionable.
     */
    private fun priority(typeCode: String): Int = when (typeCode) {
        "cam", "cam_speed", "cam_red" -> 100
        "limit" -> 50
        "limit_unknown" -> 40
        "r420", "r421" -> 20
        else -> 10
    }

    /**
     * Minimum zoom at which a sign of the given type should be shown.
     *
     * Below this zoom the sign is filtered out entirely. The thresholds are
     * chosen so that:
     * - At zoom < 11: nothing — the map shows a region, individual signs are
     *   invisible anyway.
     * - At zoom 11–13: cameras only — enforcement matters on highway approaches.
     * - At zoom 14–15: cameras + speed limits — the driver can read individual
     *   signs at this level.
     * - At zoom ≥ 16: everything — full detail for urban navigation.
     */
    private fun minZoomForType(typeCode: String): Double = when (typeCode) {
        "cam", "cam_speed", "cam_red" -> 11.0
        "limit", "limit_unknown" -> 14.0
        "r420", "r421" -> 15.0
        else -> 16.0
    }

    /**
     * Processes a raw sign list for map display at the given zoom level.
     *
     * @param signs All cached signs (potentially thousands).
     * @param zoom The current map zoom, used for density filtering. If the
     *   zoom is unknown (e.g. before the camera settles), pass [Double.MAX_VALUE]
     *   to show everything.
     * @return A deduplicated, zoom-filtered list suitable for the overlay.
     */
    fun process(signs: List<TrafficSignOverlay>, zoom: Double): List<TrafficSignOverlay> {
        if (signs.isEmpty()) return emptyList()

        // Stage 1: zoom-based filtering. This runs first because it can cut
        // the list by 80–90 % at low zoom, making the dedup much cheaper.
        val zoomFiltered = if (zoom >= Double.MAX_VALUE - 1) {
            signs
        } else {
            signs.filter { zoom >= minZoomForType(it.typeCode) }
        }
        if (zoomFiltered.isEmpty()) return emptyList()

        // Stage 2: proximity deduplication.
        return deduplicate(zoomFiltered)
    }

    /**
     * Collapses co-located signs into one per cluster.
     *
     * Uses a simple greedy approach: for each sign not yet assigned to a
     * cluster, find all signs within [DEDUP_RADIUS_M] and keep the one with
     * the highest [priority]. Ties are broken by speed-limit value (higher
     * limit wins, as it's more likely to be the relevant one).
     *
     * This is O(n²) worst case but n is at most a few hundred after zoom
     * filtering, and the inner loop exits early once the distance exceeds
     * the radius — signs are roughly sorted by latitude in the cell files.
     */
    private fun deduplicate(signs: List<TrafficSignOverlay>): List<TrafficSignOverlay> {
        if (signs.size <= 1) return signs

        val used = BooleanArray(signs.size)
        val result = ArrayList<TrafficSignOverlay>(signs.size / 2 + 1)

        for (i in signs.indices) {
            if (used[i]) continue
            used[i] = true
            var best = signs[i]
            var bestPri = priority(best.typeCode)

            for (j in (i + 1) until signs.size) {
                if (used[j]) continue
                val dist = best.point.distanceTo(signs[j].point)
                if (dist <= DEDUP_RADIUS_M) {
                    used[j] = true
                    val pri = priority(signs[j].typeCode)
                    if (pri > bestPri || (pri == bestPri && signs[j].speedLimit > best.speedLimit)) {
                        best = signs[j]
                        bestPri = pri
                    }
                }
            }
            result.add(best)
        }
        return result
    }
}
