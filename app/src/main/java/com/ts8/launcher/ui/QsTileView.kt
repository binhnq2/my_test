package com.ts8.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ts8.launcher.R

/**
 * One quick-settings tile: an icon, a label, and the platform's current state.
 *
 * **Why a custom view rather than four copies in the layout.** The tile's job is
 * to keep three things consistent — the icon tint, the value text, and whether
 * the control is offered at all — and a copy per tile is how one of them ends up
 * showing "Bật" while its radio is off. The view exposes exactly one binding
 * call, [bind], so a screen cannot update two of the three.
 *
 * **The value is never an XML default.** It starts blank and stays blank until
 * the screen has read the real state. A tile that says "Bật" before anything has
 * been queried is the fabricated status the spec forbids, and on this unit a
 * firmware that omits a service would keep saying it forever.
 */
class QsTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val icon: ImageView
    private val label: TextView
    private val value: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_qs_tile, this, true)

        icon = findViewById(R.id.tileIcon)
        label = findViewById(R.id.tileLabel)
        value = findViewById(R.id.tileValue)
        // The merge inflates into this view, so the row's own padding and
        // background come from the style; nothing here overrides them.

        // `recycle()`, not `use {}`: `TypedArray.close()` only exists from
        // API 31, and this APK runs on 23.
        val a = context.obtainStyledAttributes(attrs, R.styleable.QsTileView)
        try {
            val iconRes = a.getResourceId(R.styleable.QsTileView_qsIcon, 0)
            if (iconRes != 0) icon.setImageResource(iconRes)
            val labelRes = a.getResourceId(R.styleable.QsTileView_qsLabel, 0)
            if (labelRes != 0) label.setText(labelRes)
        } finally {
            a.recycle()
        }
    }

    /**
     * Shows the current state.
     *
     * @param state one of [State]. The tile is what decides how a state looks;
     *        the screen never sets a colour or an alpha directly, so a
     *        `UNAVAILABLE` tile cannot accidentally be drawn as `ON`.
     */
    fun bind(state: State, valueText: String? = null) {
        when (state) {
            State.ON -> {
                icon.setColorFilter(context.getColor(R.color.accent))
                value.setTextColor(context.getColor(R.color.accent_text))
                value.text = valueText ?: context.getString(R.string.qs_on)
                alpha = 1f
                isEnabled = true
            }
            State.OFF -> {
                icon.setColorFilter(context.getColor(R.color.text_secondary))
                value.setTextColor(context.getColor(R.color.text_tertiary))
                value.text = valueText ?: context.getString(R.string.qs_off)
                alpha = 1f
                isEnabled = true
            }
            State.UNAVAILABLE -> {
                // Dimmed *and* not clickable: the platform cannot do this, so
                // offering a tappable tile would be a dead control.
                icon.setColorFilter(context.getColor(R.color.text_tertiary))
                value.setTextColor(context.getColor(R.color.text_tertiary))
                value.text = valueText ?: context.getString(R.string.qs_unavailable)
                alpha = 0.45f
                isEnabled = false
            }
            State.NEEDS_PERMISSION -> {
                // Offered, and tappable, because tapping asks for the grant.
                icon.setColorFilter(context.getColor(R.color.text_tertiary))
                value.setTextColor(context.getColor(R.color.text_tertiary))
                value.text = valueText ?: context.getString(R.string.qs_permission_required)
                alpha = 1f
                isEnabled = true
            }
        }
    }

    /** What a tile is showing. */
    enum class State { ON, OFF, UNAVAILABLE, NEEDS_PERMISSION }
}
