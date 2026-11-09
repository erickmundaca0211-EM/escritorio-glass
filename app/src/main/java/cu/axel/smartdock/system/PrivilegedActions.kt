package cu.axel.smartdock.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display

/**
 * The single entry point for privileged actions in Escritorio Glass (CLAUDE.md, "Reglas de seguridad").
 *
 * Only the actions listed in [Action] exist. Each one is a fixed operation with validated numeric
 * arguments: no text from the user ever reaches a shell command or a setting name. Every change made
 * here must be undoable by another action of this class.
 */
object PrivilegedActions {
    private const val TAG = "EG"

    /** Whitelist of privileged actions. Add new ones here, never call privileged APIs elsewhere. */
    enum class Action {
        /** Force the default display density (needs WRITE_SECURE_SETTINGS). */
        SET_DISPLAY_DENSITY,

        /** Remove our forced density so the display goes back to its own (needs WRITE_SECURE_SETTINGS). */
        CLEAR_DISPLAY_DENSITY,

        /**
         * Turn Android's Night Light on or off ("Protección visual", docs/SPIKES.md d). The user toggles
         * it back from the same button (needs WRITE_SECURE_SETTINGS).
         */
        SET_NIGHT_DISPLAY
    }

    /** Settings.Secure key of Android's Night Light (hidden constant NIGHT_DISPLAY_ACTIVATED). */
    private const val NIGHT_DISPLAY_ACTIVATED = "night_display_activated"

    /** Lowest density we ever force, as a fraction of the physical one: below this text is unreadable. */
    const val MIN_DENSITY_FRACTION = 0.5f

    /** Highest density we ever force (a user's own "large display size" can be above the physical one). */
    private const val MAX_DENSITY_FRACTION = 2f

    fun isAllowed(context: Context, action: Action): Boolean = when (action) {
        Action.SET_DISPLAY_DENSITY, Action.CLEAR_DISPLAY_DENSITY, Action.SET_NIGHT_DISPLAY ->
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED
    }

    fun isNightDisplayOn(context: Context): Boolean = try {
        Settings.Secure.getInt(context.contentResolver, NIGHT_DISPLAY_ACTIVATED, 0) == 1
    } catch (e: SecurityException) {
        false
    }

    fun setNightDisplay(context: Context, on: Boolean): Boolean {
        if (!isAllowed(context, Action.SET_NIGHT_DISPLAY)) return false
        return try {
            Settings.Secure.putInt(context.contentResolver, NIGHT_DISPLAY_ACTIVATED, if (on) 1 else 0)
        } catch (e: Exception) {
            Log.w(TAG, "privileged: night display failed", e)
            false
        }
    }

    /** Physical (factory) density of the default display. */
    val physicalDensity: Int
        get() = DisplayMetrics.DENSITY_DEVICE_STABLE

    /**
     * Density currently forced on the default display, or null when it uses its own. This is what the
     * user had before we touched it (for example the "Display size" setting), so it can be restored.
     */
    fun forcedDisplayDensity(): Int? = try {
        val wm = windowManagerService()
        val initial = wm.javaClass.getMethod("getInitialDisplayDensity", Int::class.javaPrimitiveType)
            .invoke(wm, Display.DEFAULT_DISPLAY) as Int
        val base = wm.javaClass.getMethod("getBaseDisplayDensity", Int::class.javaPrimitiveType)
            .invoke(wm, Display.DEFAULT_DISPLAY) as Int
        if (base != initial) base else null
    } catch (e: Exception) {
        Log.w(TAG, "privileged: cannot read the display density", e)
        null
    }

    fun setDisplayDensity(context: Context, density: Int): Boolean {
        if (!isAllowed(context, Action.SET_DISPLAY_DENSITY)) return false
        val lowest = (physicalDensity * MIN_DENSITY_FRACTION).toInt()
        val highest = (physicalDensity * MAX_DENSITY_FRACTION).toInt()
        if (density !in lowest..highest) {
            Log.w(TAG, "privileged: density $density outside $lowest..$highest, ignored")
            return false
        }
        return invokeOnWindowManager(
            "setForcedDisplayDensityForUser",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf(Display.DEFAULT_DISPLAY, density, myUserId())
        )
    }

    fun clearDisplayDensity(context: Context): Boolean {
        if (!isAllowed(context, Action.CLEAR_DISPLAY_DENSITY)) return false
        return invokeOnWindowManager(
            "clearForcedDisplayDensityForUser",
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf(Display.DEFAULT_DISPLAY, myUserId())
        )
    }

    private fun invokeOnWindowManager(name: String, types: Array<Class<*>>, args: Array<Any>): Boolean =
        try {
            val wm = windowManagerService()
            wm.javaClass.getMethod(name, *types).invoke(wm, *args)
            true
        } catch (e: Exception) {
            Log.w(TAG, "privileged: $name failed", e)
            false
        }

    private fun windowManagerService(): Any =
        Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService")
            .invoke(null)!!

    private fun myUserId(): Int = try {
        Class.forName("android.os.UserHandle").getMethod("myUserId").invoke(null) as Int
    } catch (_: Exception) {
        -2 // UserHandle.USER_CURRENT, what "wm density" uses
    }
}
