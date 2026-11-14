package cu.axel.smartdock.dialogs

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import cu.axel.smartdock.R

class DockLayoutDialog(context: Context, asOverlay: Boolean = false) {
    init {
        val dialog = DockDialog(context, asOverlay)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        dialog.setTitle(R.string.choose_dock_layout)
        val layout = sharedPreferences.getInt("dock_layout", -1)
        dialog.setSingleChoiceItems(R.array.layouts, layout) { _, which -> applyLayout(sharedPreferences, which) }
        dialog.setPositiveButton(R.string.ok, null)
        dialog.show()
    }

    companion object {
        /** "Escritorio" in R.array.layouts: what Escritorio Glass starts with (the others stay in Apariencia). */
        const val LAYOUT_DESKTOP = 2

        /** Saves the settings of layout [which] (0 phone, 1 tablet, 2 desktop). */
        fun applyLayout(sharedPreferences: SharedPreferences, which: Int) {
            val editor = sharedPreferences.edit()
            editor.putBoolean("enable_nav_back", which != 0)
            editor.putBoolean("enable_nav_home", which != 0)
            editor.putBoolean("enable_nav_recents", which != 0)
            editor.putBoolean("enable_qs_wifi", which != 0)
            editor.putBoolean("enable_qs_vol", which != 0)
            editor.putBoolean("enable_qs_date", which != 0)
            //editor.putBoolean("enable_qs_notif", which != 0)
            editor.putBoolean("app_menu_fullscreen", which != 2)
            editor.putString("launch_mode", if (which != 2) "fullscreen" else "standard")
            editor.putString(
                "max_running_apps", when (which) {
                    0 -> "4"
                    1 -> "10"
                    else -> "15"
                }
            )
            editor.putString(
                "max_running_apps_landscape", when (which) {
                    0 -> "8"
                    1 -> "10"
                    else -> "15"
                }
            )
            editor.putString("dock_activation_area", if (which == 2) "5" else "25")
            editor.putInt("dock_layout", which)
            editor.putString("activation_method", if (which != 2) "handle" else "swipe")
            editor.putBoolean("show_notifications", which != 0)
            editor.putBoolean("enable_qs_pin", which != 2)
            editor.apply()
        }
    }
}