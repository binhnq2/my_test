package com.ts8.launcher.ui

import com.ts8.core.navigation.NavigationState

/**
 * Which chrome the map screen draws in each navigation state (§5).
 *
 * **Why this is a function and not three `if` statements inside `render`.**
 * §5 states the rule the driver actually feels: **Map Browse is a full-width
 * map with absolutely no floating music card**, and **during Navigation the
 * map and the compact card are two layout regions that do not overlap**, with
 * the maneuver card and the mute / overview / end controls always visible and
 * tappable. Those are three separate facts that have to stay consistent with
 * each other and with the layout's constraints, and while they lived inline in
 * `MapActivity.render` the only way to check them was to read the code and
 * hope. As a pure function over [NavigationState] they are asserted directly
 * (`MapChromeTest`), on a plain JVM, with no Activity, no renderer and no
 * screenshot.
 *
 * **Why `ARRIVED` shows guidance chrome but not the card.** On arrival the
 * maneuver card has nothing left to say and the destination card is already
 * hidden, so the screen keeps its guidance controls (the driver still has to
 * end the route) while the music card would be the only remaining piece of
 * chrome with nothing to accompany.
 *
 * **Why `REROUTING` counts as guidance.** The old route is gone and a new one
 * is being computed; the driver is still driving to a destination, so the end
 * control must stay reachable. It is the state where a lost driver most needs
 * to be able to cancel.
 */
object MapChrome {

    /**
     * True when the maneuver card, the ETA strip and the mute / overview / end
     * column are drawn.
     *
     * These three are one group on purpose: they are the controls §5 requires
     * to be always visible during guidance, and drawing the card without the
     * buttons would leave the driver able to read the turn but not to end it.
     */
    fun showsGuidanceChrome(state: NavigationState): Boolean = when (state) {
        NavigationState.NAVIGATING,
        NavigationState.REROUTING,
        NavigationState.ARRIVED -> true

        NavigationState.IDLE,
        NavigationState.SEARCHING,
        NavigationState.DESTINATION_PREVIEW,
        NavigationState.ROUTE_CALCULATING,
        NavigationState.ROUTE_PREVIEW -> false
    }

    /**
     * True when the compact music card is drawn beside the map.
     *
     * **Off unless the driver turned it on.** §5 originally put the card in the
     * navigation composition, but that is what the driver reported as a defect:
     * "sau khi chọn lộ trình thì widget music lại hiển thị lên là sai" — the
     * moment a route was chosen the map grew a card it did not have a second
     * earlier, on top of the panel that is supposed to own that corner. The
     * card is still one tap away in `Âm nhạc` and on Home, so the default costs
     * no control; [enabled] is `AppSettings.showMusicCardOnMap`, default false.
     *
     * Even when enabled it is only drawn during guidance — never in browse and
     * never in a route preview, where the destination panel needs the space.
     */
    fun showsMusicCard(state: NavigationState, enabled: Boolean = false): Boolean {
        if (!enabled) return false
        return when (state) {
            NavigationState.NAVIGATING,
            NavigationState.REROUTING -> true

            NavigationState.IDLE,
            NavigationState.SEARCHING,
            NavigationState.DESTINATION_PREVIEW,
            NavigationState.ROUTE_CALCULATING,
            NavigationState.ROUTE_PREVIEW,
            NavigationState.ARRIVED -> false
        }
    }

    /**
     * True for every state the launcher calls Map Browse (§5's full-width map).
     *
     * Not simply `!showsMusicCard`: a route preview also has no card, but it
     * draws a destination panel and is not a browse. This names the states in
     * which the map really is the whole screen.
     */
    fun isBrowse(state: NavigationState): Boolean = when (state) {
        NavigationState.IDLE,
        NavigationState.SEARCHING,
        NavigationState.DESTINATION_PREVIEW -> true

        NavigationState.ROUTE_CALCULATING,
        NavigationState.ROUTE_PREVIEW,
        NavigationState.NAVIGATING,
        NavigationState.REROUTING,
        NavigationState.ARRIVED -> false
    }

    /**
     * True only while every accepted GPS fix should pull the camera back onto
     * the car.
     *
     * This is narrower than [isBrowse] on purpose. Once a route exists the
     * camera belongs to the route: `applyRoute` fits the whole thing so the
     * driver can see where they are going, and during guidance
     * `setCameraFollow` owns the bearing and tilt. Re-centring on the car on
     * every fix undid both — the preview snapped back to a 16.5-zoom view of
     * the car with the destination pin off screen, and a heading-up drive was
     * forced north-up once a second. A driver who pans still gets the camera
     * back via the recentre button, not via the fix stream.
     */
    fun centresOnVehicle(state: NavigationState): Boolean = when (state) {
        NavigationState.IDLE,
        NavigationState.SEARCHING -> true

        NavigationState.DESTINATION_PREVIEW,
        NavigationState.ROUTE_CALCULATING,
        NavigationState.ROUTE_PREVIEW,
        NavigationState.NAVIGATING,
        NavigationState.REROUTING,
        NavigationState.ARRIVED -> false
    }
}
