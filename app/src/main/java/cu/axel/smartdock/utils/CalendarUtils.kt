package cu.axel.smartdock.utils

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/**
 * Next calendar event for the taskbar's left block (optional READ_CALENDAR permission).
 * Only the title and start time are read; nothing is logged, stored or sent anywhere.
 */
object CalendarUtils {
    data class Event(val title: String, val begin: Long)

    private const val LOOK_AHEAD_MS = 24L * 60 * 60 * 1000

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    fun nextEvent(context: Context, now: Long = System.currentTimeMillis()): Event? {
        if (!hasPermission(context)) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + LOOK_AHEAD_MS)
        }.build()
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN)
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
                "${CalendarContract.Instances.ALL_DAY} = 0 AND " +
                "${CalendarContract.Instances.BEGIN} >= ?"
        return try {
            context.contentResolver.query(
                uri, projection, selection, arrayOf(now.toString()),
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val title = cursor.getString(0)?.trim().orEmpty()
                if (title.isEmpty()) null else Event(title, cursor.getLong(1))
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
