package cu.axel.smartdock.components

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import cu.axel.smartdock.R
import cu.axel.smartdock.system.DesktopScale
import cu.axel.smartdock.system.PrivilegedActions
import cu.axel.smartdock.utils.CallAudio
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.Haptics
import cu.axel.smartdock.utils.Utils
import kotlin.math.roundToInt

/** Sent by the system on every volume change (public action, constant hidden in the SDK). */
private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

/**
 * Escritorio Glass quick settings (docs/DISENO.md, "Ajustes rápidos"; docs/mockup/QuickSettings.png).
 *
 * Buttons change the state directly when Android allows it without Shizuku (eye comfort, landscape lock,
 * do not disturb, flashlight); the others open the matching system screen. The panel only reads the
 * states it shows; the window is added and removed by DockService.
 */
class QuickSettingsPanel(
    private val context: Context,
    private val sharedPreferences: SharedPreferences,
    private val host: Host
) {
    interface Host {
        /** Opens a system screen and closes the panel. */
        fun openFromQuickSettings(intent: Intent)

        /** Opens Escritorio Glass settings and closes the panel. */
        fun openAppSettings()
    }

    private enum class Tile(@DrawableRes val icon: Int, @StringRes val label: Int) {
        WIFI(R.drawable.ic_eg_wifi, R.string.qs_wifi),
        BLUETOOTH(R.drawable.ic_eg_bluetooth, R.string.qs_bluetooth),
        AIRPLANE(R.drawable.ic_eg_airplane, R.string.qs_airplane),
        EYE_COMFORT(R.drawable.ic_eg_eye, R.string.qs_eye_comfort),
        BATTERY_SAVER(R.drawable.ic_eg_leaf, R.string.qs_battery_saver),
        LANDSCAPE(R.drawable.ic_eg_rotate, R.string.qs_landscape),
        DND(R.drawable.ic_eg_moon, R.string.qs_dnd),
        FLASHLIGHT(R.drawable.ic_eg_flashlight, R.string.qs_flashlight),
        CAST(R.drawable.ic_eg_cast, R.string.qs_cast)
    }

    /** Buttons that switch something right here; the others open a system screen. */
    private val switches = setOf(Tile.EYE_COMFORT, Tile.LANDSCAPE, Tile.DND, Tile.FLASHLIGHT)

    val view: LinearLayout =
        LayoutInflater.from(context).inflate(R.layout.qs_panel, null) as LinearLayout

    private val tiles = HashMap<Tile, View>()
    private val accent = ColorUtils.getAccentColor(sharedPreferences)
    private val onAccent = ContextCompat.getColor(context, R.color.on_accent)
    private val resolver = context.contentResolver
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val torchCameraId: String? = findTorchCamera()
    private var torchOn = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) {
                torchOn = enabled
                renderTile(Tile.FLASHLIGHT)
            }
        }
    }

    private val brightnessSeek: SeekBar = view.findViewById(R.id.qs_brightness_seek)
    private val brightnessValue: TextView = view.findViewById(R.id.qs_brightness_value)
    private val volumeSeek: SeekBar = view.findViewById(R.id.qs_volume_seek)
    private val volumeValue: TextView = view.findViewById(R.id.qs_volume_value)
    private val callRow: View = view.findViewById(R.id.qs_call_row)
    private val callSeek: SeekBar = view.findViewById(R.id.qs_call_seek)
    private val callValue: TextView = view.findViewById(R.id.qs_call_value)
    private val scaleSeek: SeekBar = view.findViewById(R.id.qs_scale_seek)
    private val scaleValue: TextView = view.findViewById(R.id.qs_scale_value)
    private var askedForBrightness = false

    // A call starting or ending shows or hides the call volume; volume keys move the sliders
    private val modeListener = AudioManager.OnModeChangedListener { refreshVolumes() }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshVolumes()
    }

    init {
        buildTiles()
        setUpSliders()
        view.findViewById<View>(R.id.qs_edit_btn).setOnClickListener { host.openAppSettings() }
        view.findViewById<View>(R.id.qs_settings_btn).setOnClickListener {
            host.openFromQuickSettings(Intent(Settings.ACTION_SETTINGS))
        }
        try {
            cameraManager.registerTorchCallback(torchCallback, handler)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            audioManager.addOnModeChangedListener(context.mainExecutor, modeListener)
        ContextCompat.registerReceiver(
            context, volumeReceiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    /** Reads every state again (the panel opened, or Wi-Fi / Bluetooth changed). */
    fun refresh() {
        Tile.entries.forEach { renderTile(it) }
        refreshSliders()
        refreshBattery()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            audioManager.removeOnModeChangedListener(modeListener)
        try {
            context.unregisterReceiver(volumeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            cameraManager.unregisterTorchCallback(torchCallback)
        } catch (_: Exception) {
        }
    }

    // ---- buttons

    private fun buildTiles() {
        val rows = listOf<LinearLayout>(
            view.findViewById(R.id.qs_row_1), view.findViewById(R.id.qs_row_2),
            view.findViewById(R.id.qs_row_3)
        )
        val gap = Utils.dpToPx(context, 10)
        Tile.entries.forEachIndexed { index, tile ->
            val row = rows[index / 3]
            val tileView = LayoutInflater.from(context).inflate(R.layout.qs_tile, row, false)
            if (index % 3 != 2)
                (tileView.layoutParams as LinearLayout.LayoutParams).marginEnd = gap
            tileView.findViewById<ImageView>(R.id.tile_icon).setImageResource(tile.icon)
            tileView.findViewById<TextView>(R.id.tile_label).setText(tile.label)
            val button = tileView.findViewById<View>(R.id.tile_button)
            button.contentDescription = context.getString(tile.label)
            button.setOnClickListener {
                val wasOn = isOn(tile)
                onTileClicked(tile)
                if (tile in switches) Haptics.toggle(it, !wasOn) else Haptics.tap(it)
            }
            button.setOnLongClickListener {
                settingsFor(tile)?.let { intent -> host.openFromQuickSettings(intent) }
                true
            }
            row.addView(tileView)
            tiles[tile] = tileView
        }
    }

    private fun isOn(tile: Tile): Boolean = when (tile) {
        Tile.WIFI -> context.getSystemService(WifiManager::class.java).isWifiEnabled
        Tile.BLUETOOTH ->
            context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true

        Tile.AIRPLANE -> Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        Tile.EYE_COMFORT -> PrivilegedActions.isNightDisplayOn(context)
        Tile.BATTERY_SAVER -> context.getSystemService(PowerManager::class.java).isPowerSaveMode ||
                readHyperOsPowerSave()

        Tile.LANDSCAPE -> sharedPreferences.getBoolean("lock_landscape", false)
        Tile.DND -> notificationManager.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }

        Tile.FLASHLIGHT -> torchOn
        Tile.CAST -> false
    }

    /** HyperOS keeps its own battery saver flag (see GlassController). */
    private fun readHyperOsPowerSave(): Boolean = try {
        Settings.System.getInt(resolver, "POWER_SAVE_MODE_OPEN", 0) == 1
    } catch (_: Exception) {
        false
    }

    private fun onTileClicked(tile: Tile) {
        when (tile) {
            // Android does not let apps switch these without Shizuku: open the system screen
            Tile.WIFI -> host.openFromQuickSettings(Intent(Settings.Panel.ACTION_WIFI))
            Tile.BLUETOOTH -> host.openFromQuickSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            Tile.AIRPLANE -> host.openFromQuickSettings(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            Tile.BATTERY_SAVER -> host.openFromQuickSettings(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            Tile.CAST -> host.openFromQuickSettings(Intent(Settings.ACTION_CAST_SETTINGS))

            Tile.EYE_COMFORT ->
                if (!PrivilegedActions.setNightDisplay(context, !isOn(tile)))
                    host.openFromQuickSettings(Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS))

            Tile.LANDSCAPE -> sharedPreferences.edit {
                putBoolean("lock_landscape", !sharedPreferences.getBoolean("lock_landscape", false))
            }

            // Allowed because the app is the notification listener
            Tile.DND -> if (notificationManager.isNotificationPolicyAccessGranted)
                notificationManager.setInterruptionFilter(
                    if (isOn(tile)) NotificationManager.INTERRUPTION_FILTER_ALL
                    else NotificationManager.INTERRUPTION_FILTER_PRIORITY
                )
            else
                host.openFromQuickSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

            Tile.FLASHLIGHT -> torchCameraId?.let {
                try {
                    cameraManager.setTorchMode(it, !torchOn)
                } catch (_: CameraAccessException) {
                } catch (_: IllegalArgumentException) {
                }
            }
        }
        // Settings apply asynchronously: read them again a moment later
        renderTile(tile)
        handler.postDelayed({ renderTile(tile) }, 400)
    }

    /** Long press: the full settings page of the button, when there is one. */
    private fun settingsFor(tile: Tile): Intent? = when (tile) {
        Tile.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        Tile.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        Tile.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        Tile.EYE_COMFORT -> Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS)
        Tile.BATTERY_SAVER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        Tile.DND -> Intent("android.settings.ZEN_MODE_SETTINGS")
        Tile.CAST -> Intent(Settings.ACTION_CAST_SETTINGS)
        Tile.LANDSCAPE, Tile.FLASHLIGHT -> null
    }

    private fun renderTile(tile: Tile) {
        val tileView = tiles[tile] ?: return
        val on = isOn(tile)
        val button = tileView.findViewById<View>(R.id.tile_button)
        button.isActivated = on
        button.background = if (on) GradientDrawable().apply {
            setColor(accent)
            cornerRadius = Utils.dpToPx(context, 10).toFloat()
        } else ContextCompat.getDrawable(context, R.drawable.qs_tile_bg)
        tileView.findViewById<ImageView>(R.id.tile_icon).imageTintList =
            ColorStateList.valueOf(if (on) onAccent else 0xFFFFFFFF.toInt())
    }

    private fun findTorchCamera(): String? = try {
        cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: Exception) {
        null
    }

    // ---- sliders

    private fun setUpSliders() {
        val accentList = ColorStateList.valueOf(accent)
        val track = ColorStateList.valueOf(0x33FFFFFF)
        for (seekBar in listOf(brightnessSeek, volumeSeek, callSeek, scaleSeek)) {
            seekBar.progressTintList = accentList
            seekBar.thumbTintList = accentList
            seekBar.progressBackgroundTintList = track
        }

        // Brightness 1-255 (docs/SPIKES.md f), shown as a percentage
        brightnessSeek.max = 254
        brightnessSeek.setOnSeekBarChangeListener(listener { value, fromUser ->
            brightnessValue.text = percent(value, 254)
            if (fromUser) setBrightness(value + 1)
        })

        volumeSeek.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeSeek.setOnSeekBarChangeListener(listener { value, fromUser ->
            volumeValue.text = percent(value, volumeSeek.max)
            if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        })

        // Call volume never goes to zero (the system keeps a minimum so the call stays audible)
        callSeek.min = audioManager.getStreamMinVolume(CallAudio.STREAM)
        callSeek.max = audioManager.getStreamMaxVolume(CallAudio.STREAM)
        callSeek.setOnSeekBarChangeListener(listener { value, fromUser ->
            callValue.text = percent(value, callSeek.max)
            if (fromUser) audioManager.setStreamVolume(CallAudio.STREAM, value, 0)
        })

        // Desktop scale = the "app scale" (DesktopScale): applied when the slider is released
        val lowest = (PrivilegedActions.MIN_DENSITY_FRACTION * 100).toInt()
        scaleSeek.min = lowest
        scaleSeek.max = 100
        scaleSeek.isEnabled = PrivilegedActions.isAllowed(
            context, PrivilegedActions.Action.SET_DISPLAY_DENSITY
        )
        view.findViewById<View>(R.id.qs_scale_icon).alpha = if (scaleSeek.isEnabled) 1f else 0.4f
        scaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                scaleValue.text = context.getString(R.string.qs_percent, value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                sharedPreferences.edit { putInt(DesktopScale.KEY_APP_SCALE, seekBar.progress) }
            }
        })
    }

    private fun refreshSliders() {
        brightnessSeek.progress =
            (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128) - 1)
                .coerceIn(0, 254)
        brightnessValue.text = percent(brightnessSeek.progress, 254)
        refreshVolumes()
        scaleSeek.progress = DesktopScale.percent(sharedPreferences)
        scaleValue.text = context.getString(R.string.qs_percent, scaleSeek.progress)
    }

    /** Media volume, and call volume only while a call is on; a slider being dragged is left alone. */
    private fun refreshVolumes() {
        if (!volumeSeek.isPressed) {
            volumeSeek.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            volumeValue.text = percent(volumeSeek.progress, volumeSeek.max)
        }
        callRow.isVisible = CallAudio.active(audioManager)
        if (callRow.isVisible && !callSeek.isPressed) {
            callSeek.progress = audioManager.getStreamVolume(CallAudio.STREAM)
            callValue.text = percent(callSeek.progress, callSeek.max)
        }
    }

    private fun setBrightness(value: Int) {
        if (!Settings.System.canWrite(context)) {
            // Ask once per opening, not on every step of the slider
            if (!askedForBrightness) {
                askedForBrightness = true
                android.widget.Toast.makeText(
                    context, R.string.qs_brightness_permission, android.widget.Toast.LENGTH_LONG
                ).show()
                host.openFromQuickSettings(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .setData(Uri.parse("package:" + context.packageName))
                )
            }
            return
        }
        Settings.System.putInt(
            resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    private fun percent(value: Int, max: Int): String = context.getString(
        R.string.qs_percent, if (max <= 0) 0 else (value * 100f / max).roundToInt()
    )

    private fun listener(onChange: (Int, Boolean) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) =
                onChange(value, fromUser)

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        }

    // ---- footer

    private fun refreshBattery() {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        view.findViewById<ImageView>(R.id.qs_battery_icon)
            .setImageResource(Utils.getBatteryDrawable(level, batteryManager.isCharging))
        view.findViewById<TextView>(R.id.qs_battery_tv).text =
            context.getString(R.string.qs_percent, level)
    }
}
