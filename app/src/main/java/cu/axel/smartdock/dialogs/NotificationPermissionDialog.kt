package cu.axel.smartdock.dialogs

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import cu.axel.smartdock.R

class NotificationPermissionDialog(context: Context, asOverlay: Boolean = false) {
    init {
        val dialog = DockDialog(context, asOverlay)
        dialog.setTitle(R.string.notification_access)
        // The message itself says what to do with "restricted settings"
        dialog.setMessage(R.string.notification_access_desc)
        dialog.setPositiveButton(R.string.manage) { _, _ ->
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
            Toast.makeText(context, R.string.enable_access_help, Toast.LENGTH_LONG).show()
        }
        dialog.show()
    }
}
