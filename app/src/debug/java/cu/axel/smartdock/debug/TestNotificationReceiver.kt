package cu.axel.smartdock.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cu.axel.smartdock.R

/**
 * Debug builds only: posts a test notification with two buttons ("Responder" with a reply field and
 * "Marcar") so the notification column can be tried without real conversations.
 *
 *     adb shell am broadcast -n pe.vega.escritorioglass/cu.axel.smartdock.debug.TestNotificationReceiver
 *
 * Tapping a button updates the same notification to say what arrived.
 */
class TestNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)
                post(context, "Prueba EG: respuesta recibida", "Llegó: $reply", withButtons = false)
            }

            ACTION_MARK -> post(
                context, "Prueba EG: botón recibido", "Se tocó \"Marcar\"", withButtons = false
            )

            else -> post(
                context, "Prueba EG: acciones", "Prueba Responder y Marcar", withButtons = true
            )
        }
    }

    private fun post(context: Context, title: String, text: String, withButtons: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pruebas", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_eg_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        if (withButtons) {
            val reply = RemoteInput.Builder(KEY_REPLY).setLabel("Escribe una respuesta").build()
            builder.addAction(
                Notification.Action.Builder(null, "Responder", broadcast(context, ACTION_REPLY, 1))
                    .addRemoteInput(reply).build()
            )
            builder.addAction(
                Notification.Action.Builder(null, "Marcar", broadcast(context, ACTION_MARK, 2)).build()
            )
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /** Explicit and mutable: the reply text is filled in when the button is sent. */
    private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, TestNotificationReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    private companion object {
        const val CHANNEL = "eg_debug_tests"
        const val NOTIFICATION_ID = 4711
        const val KEY_REPLY = "eg_reply"
        const val ACTION_REPLY = "cu.axel.smartdock.debug.REPLY"
        const val ACTION_MARK = "cu.axel.smartdock.debug.MARK"
    }
}
