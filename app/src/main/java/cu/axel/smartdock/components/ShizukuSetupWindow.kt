package cu.axel.smartdock.components

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import cu.axel.smartdock.R
import cu.axel.smartdock.system.ShizukuState
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.Utils

/**
 * Glass card that guides the user to Shizuku: download it, start it or let Escritorio Glass use it,
 * depending on what is missing. "Ahora no" closes it; the desktop keeps working with fewer features.
 */
class ShizukuSetupWindow(
    private val context: Context,
    sharedPreferences: SharedPreferences,
    private val host: Host
) {
    interface Host {
        fun onShizukuAction(status: ShizukuState.Status)
        fun onShizukuLater()
    }

    val view: View = LayoutInflater.from(context).inflate(R.layout.shizuku_setup, null)
    private val title: TextView = view.findViewById(R.id.shizuku_title)
    private val body: TextView = view.findViewById(R.id.shizuku_body)
    private val action: TextView = view.findViewById(R.id.shizuku_action)
    private var status = ShizukuState.Status.NOT_INSTALLED

    init {
        action.background = GradientDrawable().apply {
            cornerRadius = Utils.dpToPx(context, 18).toFloat()
            setColor(ColorUtils.getAccentColor(sharedPreferences))
        }
        action.setOnClickListener { host.onShizukuAction(status) }
        view.findViewById<View>(R.id.shizuku_later).setOnClickListener { host.onShizukuLater() }
    }

    /** Shows the texts and button for [status] (READY never reaches the window). */
    fun show(status: ShizukuState.Status) {
        this.status = status
        val (titleRes, bodyRes, actionRes) = when (status) {
            ShizukuState.Status.NOT_INSTALLED ->
                Triple(R.string.shizuku_install_title, R.string.shizuku_install_body, R.string.shizuku_install_action)

            ShizukuState.Status.STOPPED ->
                Triple(R.string.shizuku_start_title, R.string.shizuku_start_body, R.string.shizuku_start_action)

            else ->
                Triple(R.string.shizuku_allow_title, R.string.shizuku_allow_body, R.string.shizuku_allow_action)
        }
        title.setText(titleRes)
        body.setText(bodyRes)
        action.setText(actionRes)
    }
}
