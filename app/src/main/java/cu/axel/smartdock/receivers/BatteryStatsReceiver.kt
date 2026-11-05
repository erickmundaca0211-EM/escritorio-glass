package cu.axel.smartdock.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.Utils

class BatteryStatsReceiver(
    private val context: Context,
    private var batteryBtn: TextView,
    var showLevel: Boolean
) : BroadcastReceiver() {
    var level = 0
    private var lastState = true

    init {
        val batteryManager = context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        update(batteryManager.isCharging)
    }

    override fun onReceive(context: Context, intent: Intent) {
        level = intent.extras!!.getInt("level")
        if (showLevel)
            batteryBtn.text = "$level%"
        update(intent.extras!!.getInt("plugged") == 0)

    }

    /** Points the receiver at the new battery view after the bar is rebuilt (e.g. a new screen scale). */
    fun attach(button: TextView) {
        batteryBtn = button
        if (showLevel)
            batteryBtn.text = "$level%"
        update(lastState)
    }

    private fun update(isPlugged: Boolean) {
        lastState = isPlugged
        if (isPlugged)
            batteryBtn.setCompoundDrawablesWithIntrinsicBounds(
                AppCompatResources.getDrawable(
                    context,
                    Utils.getBatteryDrawable(level, false)
                ), null, null, null
            )
        else {
            batteryBtn.setCompoundDrawablesWithIntrinsicBounds(
                AppCompatResources.getDrawable(
                    context,
                    Utils.getBatteryDrawable(level, true)
                ), null, null, null
            )
            if (level == 100) {
                if (Utils.shouldPlayChargeComplete)
                    DeviceUtils.playEventSound(context, "charge_complete_sound")
                Utils.shouldPlayChargeComplete = false
            }
        }
    }
}
