package cu.axel.smartdock.system

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "App scale" of the desktop (docs/PLAN.md, Fase 5): while the desktop is active the screen density is
 * lowered so apps and their windows show more content; when it stops, the user's own density comes
 * back. The taskbar keeps its own, larger scale so it stays easy to use.
 *
 * All density changes go through [PrivilegedActions].
 */
object DesktopScale {
    /** Percentage of the physical density used by apps; 100 = unchanged. */
    const val KEY_APP_SCALE = "app_scale"
    const val DEFAULT_PERCENT = 100

    /** Present only while we have changed the density: the density the user had before (0 = none). */
    private const val KEY_SAVED_DENSITY = "app_scale_saved_density"

    /** The taskbar follows the app scale only down to this percentage. */
    private const val BAR_MIN_PERCENT = 70

    fun percent(sharedPreferences: SharedPreferences): Int =
        sharedPreferences.getInt(KEY_APP_SCALE, DEFAULT_PERCENT)
            .coerceIn((PrivilegedActions.MIN_DENSITY_FRACTION * 100).toInt(), 100)

    /** Applies the app scale; the first time, it remembers the density the user had. */
    fun apply(context: Context, sharedPreferences: SharedPreferences) {
        val percent = percent(sharedPreferences)
        if (percent >= 100) {
            restore(context, sharedPreferences)
            return
        }
        if (!PrivilegedActions.isAllowed(context, PrivilegedActions.Action.SET_DISPLAY_DENSITY))
            return
        if (!sharedPreferences.contains(KEY_SAVED_DENSITY))
            sharedPreferences.edit(commit = true) {
                putInt(KEY_SAVED_DENSITY, PrivilegedActions.forcedDisplayDensity() ?: 0)
            }
        val density = (PrivilegedActions.physicalDensity * percent / 100f).roundToInt()
        if (PrivilegedActions.forcedDisplayDensity() != density)
            PrivilegedActions.setDisplayDensity(context, density)
    }

    /** Puts back the density the user had before the desktop changed it. */
    fun restore(context: Context, sharedPreferences: SharedPreferences) {
        if (!sharedPreferences.contains(KEY_SAVED_DENSITY)) return
        val saved = sharedPreferences.getInt(KEY_SAVED_DENSITY, 0)
        val restored = if (saved > 0) PrivilegedActions.setDisplayDensity(context, saved)
        else PrivilegedActions.clearDisplayDensity(context)
        if (restored)
            sharedPreferences.edit(commit = true) { remove(KEY_SAVED_DENSITY) }
    }

    /** Density for the taskbar and its panels, or null to use the system one. */
    fun barDensity(sharedPreferences: SharedPreferences): Int? {
        val percent = percent(sharedPreferences)
        if (percent >= 100) return null
        return (PrivilegedActions.physicalDensity * max(percent, BAR_MIN_PERCENT) / 100f).roundToInt()
    }
}
