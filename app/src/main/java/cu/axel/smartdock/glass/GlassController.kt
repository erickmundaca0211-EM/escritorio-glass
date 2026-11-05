package cu.axel.smartdock.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import cu.axel.smartdock.R
import java.util.WeakHashMap
import java.util.function.Consumer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single place that turns views into glass and keeps them right when something changes:
 * glass settings, system support for cross-window blur, or battery saver. HyperOS keeps blur on in
 * battery saver (docs/SPIKES.md, spike a), so we drop to level 1 ourselves.
 */
object GlassController {
    private const val TAG = "EG"

    private val surfaces = WeakHashMap<View, GlassSurface>()
    private var startedWith: Context? = null
    private var windowManager: WindowManager? = null
    private var blurAllowedBySystem = true
    private var powerSave = false

    private val blurListener = Consumer<Boolean> { enabled ->
        if (enabled != blurAllowedBySystem) {
            blurAllowedBySystem = enabled
            Log.d(TAG, "glass: system blur enabled=$enabled")
            refreshAll()
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pm = context.getSystemService(PowerManager::class.java)
            powerSave = pm.isPowerSaveMode
            Log.d(TAG, "glass: battery saver=$powerSave")
            refreshAll()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("glass_") || key == "theme")) refreshAll()
    }

    /** Starts listening for blur support, battery saver and settings. Safe to call more than once. */
    fun start(context: Context) {
        if (startedWith != null) return
        startedWith = context
        val wm = context.getSystemService(WindowManager::class.java)
        windowManager = wm
        blurAllowedBySystem = wm.isCrossWindowBlurEnabled
        wm.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
        powerSave = context.getSystemService(PowerManager::class.java).isPowerSaveMode
        ContextCompat.registerReceiver(
            context, powerReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        PreferenceManager.getDefaultSharedPreferences(context)
            .registerOnSharedPreferenceChangeListener(prefListener)
        Log.d(TAG, "glass: started blur=$blurAllowedBySystem batterySaver=$powerSave")
    }

    fun stop() {
        val context = startedWith ?: return
        windowManager?.removeCrossWindowBlurEnabledListener(blurListener)
        try {
            context.unregisterReceiver(powerReceiver)
        } catch (_: IllegalArgumentException) {
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        startedWith = null
        windowManager = null
    }

    /** Makes [view] a glass surface; it is redrawn whenever it is attached to a (new) window. */
    fun apply(view: View, surface: GlassSurface) {
        surfaces[view] = surface
        if (view.getTag(R.id.glass_attach_listener) == null) {
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    surfaces[v]?.let { render(v, it) }
                }

                override fun onViewDetachedFromWindow(v: View) {}
            }
            view.addOnAttachStateChangeListener(listener)
            view.setTag(R.id.glass_attach_listener, listener)
        }
        render(view, surface)
    }

    /** Stops treating [view] as glass (another theme was chosen) and restores [fallback] as background. */
    fun release(view: View, fallback: Int) {
        if (surfaces.remove(view) != null) view.setBackgroundResource(fallback)
    }

    fun isGlass(view: View) = surfaces.containsKey(view)

    fun refreshAll() {
        for ((view, surface) in surfaces.entries.toList()) render(view, surface)
    }

    private fun render(view: View, surface: GlassSurface) {
        val sp = PreferenceManager.getDefaultSharedPreferences(view.context)
        if (sp.getString("theme", "glass") != "glass") return
        val settings = GlassSettings.read(sp)
        val density = view.resources.displayMetrics.density
        val radius = surface.radiusDp * density

        val wantsBlur = settings.quality != GlassQuality.OFF && settings.blurRadiusDp > 0 &&
                blurAllowedBySystem && !powerSave
        val blur: Drawable? = if (wantsBlur)
            GlassBlur.create(view, (settings.blurRadiusDp * density).roundToInt(), radius)
        else null

        // Without blur the tint gets denser so text stays readable (contrast 4.5:1, docs/DISENO.md)
        val opacity = when {
            settings.quality == GlassQuality.OFF -> 0.92f
            blur == null -> min(settings.tintOpacity + 0.25f, 0.92f)
            else -> settings.tintOpacity
        }
        val tint = ((opacity * 255).roundToInt() shl 24) or (GlassDrawable.TINT and 0x00FFFFFF)
        val glass = GlassDrawable(radius, tint, density)

        view.background = if (blur != null) LayerDrawable(arrayOf(blur, glass)) else glass
        view.clipToOutline = radius > 0f
    }
}
