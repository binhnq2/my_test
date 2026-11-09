package com.ts8.launcher.ui

import android.os.Build
import android.view.View
import android.view.ViewGroup
import com.ts8.launcher.R

/**
 * How far below the top of the window the launcher's own chrome must start.
 *
 * **Why this is a runtime number and not just `@dimen/vendor_bar_inset`.** The
 * launcher draws behind the vendor status bar on purpose (`BaseScreenActivity`
 * sets `LAYOUT_FULLSCREEN` so a screen change does not shift the whole panel),
 * which means the top of every window is covered by a bar this app does not
 * own. The TS8's own bar is 32 dp and `vendor_bar_inset` records that; the
 * emulator and any other unit report theirs through the window insets, and a
 * hard-coded 32 dp would leave the weather chip half-hidden on a 56 dp bar.
 *
 * The larger of the two wins: the declared value covers a vendor that reports
 * nothing, and the system inset covers a vendor that is taller than the
 * documented one. Neither is ever smaller than what is actually covered.
 */
internal fun View.vendorBarInsetPx(): Int {
    val declared = resources.getDimensionPixelSize(R.dimen.vendor_bar_inset)
    val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        rootWindowInsets?.systemWindowInsetTop ?: 0
    } else {
        0
    }
    return maxOf(declared, system)
}

/**
 * Moves this view down by [px], leaving its other margins alone.
 *
 * Used for views that are positioned by a parent (`FrameLayout.gravity`,
 * `ConstraintLayout` constraints) rather than by padding, where adding top
 * padding would shrink the content area instead of moving the view.
 */
internal fun View.setTopInset(px: Int) {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (params.topMargin == px) return
    params.topMargin = px
    layoutParams = params
}
