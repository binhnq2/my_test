package com.ts8.launcher.ui

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.ts8.launcher.R

/**
 * The status row: clock, date, and the connectivity icons the design shows.
 *
 * **Every icon is read from a real API.** The design draws Wi-Fi, signal,
 * Bluetooth and battery; a launcher that draws them in a fixed "on" state is
 * lying to the driver about whether the car has a connection. So each icon is
 * tinted `text_tertiary` when the radio is off and `text_secondary` when it is
 * on, and the receivers are registered on the view's own window so a screen
 * that is not showing costs nothing.
 *
 * **API 23 note.** `ConnectivityManager.getActiveNetwork()` and
 * `NetworkInfo.isConnected` are the only APIs available here; the
 * `NetworkCallback`-based path and `getNetworkCapabilities` do not exist until
 * API 23/24 respectively in a form that helps. `WifiManager.isWifiEnabled`
 * needs no permission beyond `ACCESS_WIFI_STATE` when only read.
 */
class StatusBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val clock: TextView
    private val date: TextView
    private val iconWifi: ImageView
    private val iconSignal: ImageView
    private val iconBluetooth: ImageView
    private val iconBattery: ImageView

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> refreshClock()
                else -> refreshState()
            }
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_status_bar, this, true)
        clock = findViewById(R.id.clockText)
        date = findViewById(R.id.dateText)
        iconWifi = findViewById(R.id.iconWifi)
        iconSignal = findViewById(R.id.iconSignal)
        iconBluetooth = findViewById(R.id.iconBluetooth)
        iconBattery = findViewById(R.id.iconBattery)

        // The design has no settings affordance anywhere else on the seven
        // screens, and every one of them draws this icon row. Making it the
        // entry point means Quick Settings is reachable from wherever the
        // driver is without adding a control the design does not have.
        findViewById<View>(R.id.statusIcons)?.setOnClickListener {
            runCatching {
                context.startActivity(QuickSettingsActivity.intentTo(context))
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!registered) {
            // TIME_TICK fires once a minute, which is exactly the clock's
            // resolution; a Handler would wake the CPU at the same rate for
            // more code.
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            context.registerReceiver(receiver, filter)
            registered = true
        }
        refreshClock()
        refreshState()
    }

    override fun onDetachedFromWindow() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        super.onDetachedFromWindow()
    }

    fun refreshClock() {
        clock.text = ClockFormat.time()
        date.text = ClockFormat.date()
    }

    /**
     * Re-reads every radio state.
     *
     * Each read is wrapped: a vendor firmware that stripped a service, or
     * denied a read, must leave the icon dim rather than crash the launcher.
     */
    fun refreshState() {
        val ctx = context

        val wifiOn = runCatching {
            val wm = ctx.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.isWifiEnabled == true
        }.getOrDefault(false)
        tint(iconWifi, wifiOn)

        val mobileOn = runCatching {
            val cm = ctx.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            @Suppress("DEPRECATION")
            val info: NetworkInfo? = cm?.activeNetworkInfo
            info != null && info.isConnected && info.type == ConnectivityManager.TYPE_MOBILE
        }.getOrDefault(false)
        tint(iconSignal, mobileOn)

        val btOn = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        }.getOrDefault(false)
        tint(iconBluetooth, btOn)

        // Battery: the icon is always shown, but a charging unit is brighter
        // because on a head unit "is it charging" is the question that matters.
        val charging = runCatching {
            val bm = ctx.applicationContext
                .getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            @Suppress("DEPRECATION")
            bm?.isCharging == true
        }.getOrDefault(false)
        tint(iconBattery, charging)
    }

    private fun tint(view: ImageView, on: Boolean) {
        view.setColorFilter(
            context.getColor(if (on) R.color.text_secondary else R.color.text_tertiary)
        )
    }
}
