package com.ts8.core.speed

/**
 * Resolves the speed limit for the road the car is on, with provenance.
 *
 * **Why this cannot hard-code a limit per road class.** The design shows a `60`
 * roundel, and the obvious shortcut is "residential = 50, motorway = 80". That
 * is fabricating a safety-relevant number. The pack's routing graph carries a
 * per-edge `speedKph`, but the pack builder fills it from the OSM `maxspeed` tag
 * *where one exists* and from a per-class default otherwise (see
 * `docs/OFFLINE_SEARCH_ROUTING.md` §2.1). Both are real data about this road, so
 * both are usable — but they are not equally trustworthy, and this class is
 * where that difference is recorded instead of being flattened away.
 *
 * **Where each confidence comes from.** A limit tagged on the way is a posted
 * sign: [SpeedLimit.Confidence.HIGH]. A limit the pack builder inferred from the
 * road class is a reasonable default but not a sign: [SpeedLimit.Confidence.MEDIUM].
 * A limit from the vehicle bus is what the car's own system believes:
 * [SpeedLimit.Confidence.HIGH]. Nothing is upgraded silently, and a lookup with
 * no answer at all produces [SpeedLimit.NONE], which the UI renders as `--` and
 * the detector treats as "never alert".
 */
class SpeedLimitResolver(private val clock: () -> Long) {

    private var current: SpeedLimit = SpeedLimit.NONE

    /** The limit that applies right now. [SpeedLimit.NONE] means "no data". */
    fun current(): SpeedLimit = current

    /**
     * A limit from the routing graph's per-edge speed.
     *
     * Exposed as MEDIUM because this pack mixes tagged and class-default
     * values in one field and there is no bit saying which is which; claiming
     * HIGH would overstate the data. When the pack builder starts carrying a
     * tag flag, [setTagged] is the path that reports HIGH.
     */
    fun setPackLimit(speedKph: Int): SpeedLimit {
        current = if (speedKph <= 0) {
            SpeedLimit.NONE
        } else {
            SpeedLimit(
                limitKmh = speedKph,
                source = LimitSource.ROUTING_GRAPH,
                resolvedAtElapsedMs = clock(),
                confidence = SpeedLimit.Confidence.MEDIUM
            )
        }
        return current
    }

    /**
     * A limit read from an OSM `maxspeed` tag carried through the pack.
     *
     * HIGH confidence because it is a posted sign rather than an inference.
     */
    fun setTagged(speedKph: Int): SpeedLimit {
        current = if (speedKph <= 0) {
            SpeedLimit.NONE
        } else {
            SpeedLimit(
                limitKmh = speedKph,
                source = LimitSource.ROUTING_GRAPH,
                resolvedAtElapsedMs = clock(),
                confidence = SpeedLimit.Confidence.HIGH
            )
        }
        return current
    }

    /** A limit published by a verified vehicle-bus bridge; outranks the pack. */
    fun setVehicleLimit(speedKph: Int): SpeedLimit {
        current = if (speedKph <= 0) {
            SpeedLimit.NONE
        } else {
            SpeedLimit(
                limitKmh = speedKph,
                source = LimitSource.VEHICLE_BUS,
                resolvedAtElapsedMs = clock(),
                confidence = SpeedLimit.Confidence.HIGH
            )
        }
        return current
    }

    /**
     * Called when the car leaves the road the limit was resolved for.
     *
     * Clearing rather than holding: carrying a 50 from the street the car was
     * on into the motorway it just joined would produce an alert on a legal
     * speed, which is the fastest way to make a driver switch the feature off.
     */
    fun clear() {
        current = SpeedLimit.NONE
    }
}
