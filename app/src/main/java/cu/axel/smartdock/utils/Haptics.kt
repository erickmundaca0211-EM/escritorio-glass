package cu.axel.smartdock.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Short vibrations for the desktop, through the view so they follow the user's system setting
 * ("Respuesta táctil"): when it is off, nothing vibrates. Long presses already vibrate on their own.
 */
object Haptics {

    /** A button that only opens something. */
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** A switch that just turned [on] or off. */
    fun toggle(view: View, on: Boolean) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else HapticFeedbackConstants.CONTEXT_CLICK
        )
    }

    /** A drag that just went past the point where letting go does something ([crossed]), or back. */
    fun threshold(view: View, crossed: Boolean) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                if (crossed) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            else HapticFeedbackConstants.CLOCK_TICK
        )
    }
}
