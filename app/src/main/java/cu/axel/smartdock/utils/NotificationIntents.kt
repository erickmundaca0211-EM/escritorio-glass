package cu.axel.smartdock.utils

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** Opens what a notification points to when the user taps it in Escritorio Glass. */
object NotificationIntents {

    /**
     * Sends a notification's [intent] (its content or one of its action buttons), with [fillIn]
     * carrying a reply typed by the user, if any.
     *
     * Since Android 14, a PendingIntent sent by another app only opens an activity from the
     * background when the sender says so: without these options the tap does nothing. Call this only
     * from a user's tap, never on its own.
     */
    fun sendFromTap(context: Context, intent: PendingIntent, fillIn: Intent? = null): Boolean = try {
        intent.send(context, 0, fillIn, null, null, null, tapOptions())
        true
    } catch (_: PendingIntent.CanceledException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun tapOptions(): Bundle? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            ).toBundle()

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            ).toBundle()

        else -> null
    }
}
