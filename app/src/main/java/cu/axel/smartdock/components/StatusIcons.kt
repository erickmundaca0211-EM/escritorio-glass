package cu.axel.smartdock.components

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import cu.axel.smartdock.R
import cu.axel.smartdock.glass.BatteryDrawable
import cu.axel.smartdock.glass.CellDrawable
import cu.axel.smartdock.glass.VolumeDrawable
import cu.axel.smartdock.glass.WifiDrawable
import cu.axel.smartdock.utils.CallAudio

/**
 * Keeps the taskbar status icons live: Wi-Fi or mobile signal (whichever carries the connection),
 * volume level and battery. Only system state is read (no permissions beyond network and Wi-Fi state)
 * and nothing is stored or logged.
 */
class StatusIcons(
    private val context: Context,
    private val networkView: ImageView,
    private val volumeView: ImageView,
    private val batteryView: TextView
) {
    private val color = ContextCompat.getColor(context, R.color.glass_text_primary)
    private val density = context.resources.displayMetrics.density
    private val wifi = WifiDrawable(density, color)
    private val cell = CellDrawable(density, color)
    private val volume = VolumeDrawable(density, color)
    private val battery = BatteryDrawable(density, color, batteryView.typeface)

    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    // Default network as last reported
    private var onWifi = false
    private var onCell = false
    private var validated = false
    private var wifiLevel = 0
    private var cellLevel = 0
    private var started = false

    init {
        batteryView.text = ""
        batteryView.setCompoundDrawablesWithIntrinsicBounds(battery, null, null, null)
        volumeView.setImageDrawable(volume)
    }

    fun start() {
        if (started) return
        started = true
        connectivity.registerDefaultNetworkCallback(networkCallback, handler)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(VOLUME_CHANGED)
            addAction(STREAM_MUTE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // HyperOS keeps its own battery saver switch (docs/SPIKES.md)
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(HYPEROS_POWER_SAVE), false, saverObserver
        )
        listenToCellSignal(true)
        // During a call the speaker shows the call volume (the volume keys change that one)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            audio.addOnModeChangedListener(context.mainExecutor, modeListener)
        refreshNetwork()
        refreshVolume()
        refreshBattery(null)
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
        }
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        context.contentResolver.unregisterContentObserver(saverObserver)
        listenToCellSignal(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            audio.removeOnModeChangedListener(modeListener)
        handler.removeCallbacksAndMessages(null)
    }

    // ---- network

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            onCell = !onWifi && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (onWifi && caps.signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED)
                wifiLevel = wifiLevelOf(caps.signalStrength)
            refreshNetwork()
        }

        override fun onLost(network: Network) {
            onWifi = false
            onCell = false
            validated = false
            refreshNetwork()
        }
    }

    private fun wifiLevelOf(rssi: Int): Int {
        val max = wifiManager.maxSignalLevel.coerceAtLeast(1)
        return (wifiManager.calculateSignalLevel(rssi) * 4 + max / 2) / max
    }

    private fun refreshNetwork() {
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        when {
            onWifi -> {
                wifi.set(
                    if (validated) WifiDrawable.State.CONNECTED else WifiDrawable.State.NO_INTERNET,
                    wifiLevel
                )
                networkView.setImageDrawable(wifi)
            }
            // Wi-Fi off and the phone online through the mobile network
            onCell -> {
                cell.set(cellLevel, noService = false)
                networkView.setImageDrawable(cell)
            }

            airplane -> networkView.setImageResource(R.drawable.ic_eg_airplane)
            // Wi-Fi on but not connected: dim fan; nothing on: crossed out
            wifiManager.isWifiEnabled -> {
                wifi.set(WifiDrawable.State.NO_INTERNET, 0)
                networkView.setImageDrawable(wifi)
            }

            else -> {
                wifi.set(WifiDrawable.State.OFF, 0)
                networkView.setImageDrawable(wifi)
            }
        }
    }

    @Suppress("DEPRECATION")
    private val phoneStateListener: Any by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) =
                    onCellSignal(signalStrength)
            }
        else object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                signalStrength?.let { onCellSignal(it) }
            }
        }
    }

    private fun onCellSignal(signalStrength: SignalStrength) {
        handler.post {
            cellLevel = signalStrength.level
            if (onCell) refreshNetwork()
        }
    }

    @SuppressLint("MissingPermission") // signal strength needs no permission
    @Suppress("DEPRECATION")
    private fun listenToCellSignal(listen: Boolean) {
        val tm = telephony ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = phoneStateListener as TelephonyCallback
                if (listen) tm.registerTelephonyCallback(context.mainExecutor, callback)
                else tm.unregisterTelephonyCallback(callback)
            } else tm.listen(
                phoneStateListener as PhoneStateListener,
                if (listen) PhoneStateListener.LISTEN_SIGNAL_STRENGTHS else PhoneStateListener.LISTEN_NONE
            )
        } catch (_: SecurityException) {
            // Without signal updates the bars just keep their last level
        }
        if (listen) tm.signalStrength?.let { cellLevel = it.level }
    }

    // ---- volume and battery

    private val modeListener by lazy { AudioManager.OnModeChangedListener { refreshVolume() } }

    private fun refreshVolume() {
        val stream = if (CallAudio.active(audio)) CallAudio.STREAM else AudioManager.STREAM_MUSIC
        val min = audio.getStreamMinVolume(stream)
        val range = (audio.getStreamMaxVolume(stream) - min).coerceAtLeast(1)
        val now = audio.getStreamVolume(stream)
        val muted = now == 0 || audio.isStreamMute(stream)
        val step = now - min
        val waves = when {
            step * 3 <= range -> 1
            step * 3 <= range * 2 -> 2
            else -> 3
        }
        volume.set(waves, muted)
    }

    private fun refreshBattery(intent: Intent?) {
        val sticky = intent ?: ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val level = sticky?.let {
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale
        } ?: context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        battery.set(level, charging, isSaverOn())
        batteryView.contentDescription = context.getString(R.string.qs_percent, level)
    }

    private fun isSaverOn(): Boolean = power.isPowerSaveMode || try {
        Settings.System.getInt(context.contentResolver, HYPEROS_POWER_SAVE, 0) == 1
    } catch (_: Exception) {
        false
    }

    private val saverObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refreshBattery(null)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.RSSI_CHANGED_ACTION -> {
                    val rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, Int.MIN_VALUE)
                    if (rssi != Int.MIN_VALUE) wifiLevel = wifiLevelOf(rssi)
                    refreshNetwork()
                }

                WifiManager.WIFI_STATE_CHANGED_ACTION, Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                    refreshNetwork()

                VOLUME_CHANGED, STREAM_MUTE_CHANGED, AudioManager.RINGER_MODE_CHANGED_ACTION ->
                    refreshVolume()

                Intent.ACTION_BATTERY_CHANGED -> refreshBattery(intent)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> refreshBattery(null)
            }
        }
    }

    private companion object {
        /** Sent by the system on every volume change (public action, constant hidden in the SDK). */
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        const val HYPEROS_POWER_SAVE = "POWER_SAVE_MODE_OPEN"
    }
}
