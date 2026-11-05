package cu.axel.smartdock.utils

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.content.edit
import cu.axel.smartdock.R
import cu.axel.smartdock.models.App
import java.io.File
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Helpers for the Escritorio Glass Start menu (docs/DISENO.md, "Menú Inicio"). */
object StartMenuUtils {
    /** Pinned apps the first time, like the mockup's "Ancladas" (6 x 3). */
    private const val DEFAULT_PINS_MAX = 18
    private const val KEY_DEFAULT_PINS_DONE = "start_default_pins_done"

    /** "Recomendado" looks at the last 3 days of use. */
    private const val RECENT_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

    data class RecentApp(val app: App, val lastUsed: Long)

    /** Lower case without accents, so "camara" finds "Cámara". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.getDefault())

    /** Letter that groups an app in the A-Z list ("#" for names that don't start with a letter). */
    fun sectionLetter(name: String): String {
        val first = normalize(name.trim()).firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercase(Locale.getDefault()) else "#"
    }

    /**
     * Apps used most recently (UsageStats, the permission the taskbar already uses). Only the package
     * and the last-use time are read; nothing is stored or sent anywhere.
     */
    fun recentApps(
        context: Context, installed: List<App>, exclude: Set<String>, limit: Int
    ): List<RecentApp> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val lastUse = HashMap<String, Long>()
        try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - RECENT_WINDOW_MS, now)
                .forEach { stat ->
                    if (stat.lastTimeUsed > 0 && stat.totalTimeInForeground > 0)
                        lastUse[stat.packageName] =
                            maxOf(lastUse[stat.packageName] ?: 0L, stat.lastTimeUsed)
                }
        } catch (_: SecurityException) {
            return emptyList()
        }
        val byPackage = installed.filter { it.userHandle == Process.myUserHandle() }
            .associateBy { it.packageName }
        return lastUse.entries
            .filter { it.key !in exclude && it.value >= now - RECENT_WINDOW_MS }
            .sortedByDescending { it.value }
            .mapNotNull { entry -> byPackage[entry.key]?.let { RecentApp(it, entry.value) } }
            .take(limit)
    }

    /** "Ahora", "Hace 12 min", "Hace 3 h", "Ayer, 18:40" or a short date. */
    fun relativeTime(context: Context, time: Long): String {
        val now = System.currentTimeMillis()
        val minutes = ((now - time) / 60_000).coerceAtLeast(0)
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            minutes < 1 -> context.getString(R.string.recent_now)
            minutes < 60 -> context.getString(R.string.recent_minutes, minutes)
            sameDay(then, today) -> context.getString(R.string.recent_hours, minutes / 60)
            sameDay(then, yesterday) -> context.getString(
                R.string.recent_yesterday, DateFormat.getTimeFormat(context).format(then.time)
            )

            else -> DateFormat.format(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), then
            ).toString()
        }
    }

    /**
     * The first time the Start menu has nothing pinned, pin up to 18 common apps the user already has
     * (default messaging, browser, mail, camera…). Missing ones are skipped. Runs only once.
     */
    fun pinDefaultAppsOnce(context: Context, sharedPreferences: SharedPreferences): Boolean {
        if (sharedPreferences.getBoolean(KEY_DEFAULT_PINS_DONE, false)) return false
        val file = File(context.filesDir, AppUtils.PINNED_LIST)
        val alreadyPinned = file.exists() && file.readLines().any { it.isNotBlank() }
        sharedPreferences.edit { putBoolean(KEY_DEFAULT_PINS_DONE, true) }
        if (alreadyPinned) return false

        val pm = context.packageManager
        fun category(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        val candidates = mutableListOf(
            category(Intent.CATEGORY_APP_MESSAGING),
            category(Intent.CATEGORY_APP_BROWSER),
            category(Intent.CATEGORY_APP_EMAIL),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            category(Intent.CATEGORY_APP_GALLERY),
            category(Intent.CATEGORY_APP_MUSIC),
            category(Intent.CATEGORY_APP_FILES),
            category(Intent.CATEGORY_APP_CALENDAR),
            category(Intent.CATEGORY_APP_CALCULATOR),
            category(Intent.CATEGORY_APP_MAPS),
            Intent(Settings.ACTION_SETTINGS),
            category(Intent.CATEGORY_APP_CONTACTS),
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            category(Intent.CATEGORY_APP_MARKET),
            Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION),
            Intent(Intent.ACTION_DIAL)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            candidates.add(category(Intent.CATEGORY_APP_WEATHER))

        val packages = LinkedHashSet<String>()
        for (intent in candidates) {
            val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            // "android" is the chooser: no default app, take the first one that can handle it
            val pkg = if (resolved != null && resolved != "android") resolved
            else pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName
            if (pkg != null && pkg != context.packageName && pm.getLaunchIntentForPackage(pkg) != null)
                packages.add(pkg)
            if (packages.size >= DEFAULT_PINS_MAX) break
        }
        if (packages.isEmpty()) return false
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val serial = userManager.getSerialNumberForUser(Process.myUserHandle())
        // Same format as AppUtils.pinApp: "<package> <user serial>"
        file.writeText(packages.joinToString("") { "$it $serial\n" })
        return true
    }
}
