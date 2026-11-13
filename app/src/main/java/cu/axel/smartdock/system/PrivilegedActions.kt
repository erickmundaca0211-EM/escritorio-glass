package cu.axel.smartdock.system

import android.Manifest
import android.app.ActivityManager
import android.app.TaskInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import cu.axel.smartdock.wrappers.ActivityManagerWrapper
import cu.axel.smartdock.wrappers.WindowOrganizerWrapper

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
        SET_NIGHT_DISPLAY,

        /** Read the open windows (tasks) for the taskbar; nothing in them is read but app and id (Shizuku). */
        LIST_TASKS,

        /**
         * Close one window, like swiping it away in recents ("Cerrar ventana"). Only from the user's tap
         * on the taskbar menu, and only a task id the system itself listed (Shizuku).
         */
        CLOSE_TASK,

        /**
         * Send one window behind all the others, like minimizing it in Windows. Only from the user's
         * click on its taskbar button, only a task id the system itself listed; the next click brings
         * it back to the front (Shizuku).
         */
        MINIMIZE_TASK,

        /**
         * Bring one window back to the front from its taskbar button, at once (the app's own request
         * can be held back for seconds after a minimize). Only a task id the system listed (Shizuku).
         */
        RESTORE_TASK
    }

    /** Most windows the taskbar asks for at once. */
    private const val MAX_TASKS = 100

    // WindowConfiguration windowing modes (hidden constants)
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

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

        Action.LIST_TASKS, Action.CLOSE_TASK, Action.MINIMIZE_TASK, Action.RESTORE_TASK ->
            ShizukuState.isUsable()
    }

    // ---- Shizuku: the activity manager through Shizuku's binder, only for the actions above

    private var activityManager: ActivityManagerWrapper? = null

    @Synchronized
    private fun shizukuActivityManager(): ActivityManagerWrapper? {
        activityManager?.let { if (it.isAlive()) return it }
        return try {
            ActivityManagerWrapper().also { activityManager = it }
        } catch (e: Exception) {
            Log.w(TAG, "privileged: Shizuku activity manager unavailable", e)
            null
        }
    }

    private var windowOrganizer: WindowOrganizerWrapper? = null

    @Synchronized
    private fun shizukuWindowOrganizer(): WindowOrganizerWrapper? {
        windowOrganizer?.let { if (it.isAlive()) return it }
        return try {
            WindowOrganizerWrapper().also { windowOrganizer = it }
        } catch (e: Exception) {
            Log.w(TAG, "privileged: Shizuku window organizer unavailable", e)
            null
        }
    }

    /** Shizuku stopped: drop its binders (new ones are made when it starts again). */
    @Synchronized
    fun forgetShizuku() {
        activityManager = null
        windowOrganizer = null
    }

    fun runningTasks(context: Context, max: Int): List<ActivityManager.RunningTaskInfo> {
        if (!isAllowed(context, Action.LIST_TASKS) || max !in 1..MAX_TASKS) return emptyList()
        return try {
            shizukuActivityManager()?.getRunningTasks(max) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "privileged: listing tasks failed", e)
            emptyList()
        }
    }

    fun closeTask(context: Context, taskId: Int): Boolean {
        if (!isAllowed(context, Action.CLOSE_TASK) || taskId <= 0) return false
        return try {
            shizukuActivityManager()?.removeTask(taskId) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "privileged: closing task $taskId failed", e)
            false
        }
    }

    /** Place and size of each floating window we minimized, to bring it back the same (task id key). */
    private val minimizedFloating = HashMap<Int, Rect>()

    fun minimizeTask(context: Context, taskId: Int): Boolean {
        if (!isAllowed(context, Action.MINIMIZE_TASK)) return false
        val (task, token, organizer) = listedTask(taskId) ?: return false
        return try {
            val floating = windowingMode(task) == WINDOWING_MODE_FREEFORM
            val bounds = if (floating) taskBounds(task) else null
            organizer.sendToBack(token, fromFloating = bounds != null)
            if (bounds != null) minimizedFloating[taskId] = bounds else minimizedFloating.remove(taskId)
            Log.d(TAG, "privileged: task $taskId minimized (floating: ${bounds != null})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "privileged: minimizing task $taskId failed", e)
            false
        }
    }

    fun restoreTask(context: Context, taskId: Int): Boolean {
        if (!isAllowed(context, Action.RESTORE_TASK)) return false
        val (task, token, organizer) = listedTask(taskId) ?: return false
        return try {
            // Floating again only if it is still the full-screen window we made of it
            val bounds = minimizedFloating.remove(taskId)
                ?.takeIf { windowingMode(task) == WINDOWING_MODE_FULLSCREEN }
            organizer.bringToFront(taskId, token, bounds)
            Log.d(TAG, "privileged: task $taskId restored (floating: ${bounds != null})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "privileged: restoring task $taskId failed", e)
            false
        }
    }

    /**
     * The task the system itself lists with [taskId], with its window token (never built by us) and
     * the organizer that moves it.
     */
    private fun listedTask(taskId: Int): Triple<ActivityManager.RunningTaskInfo, Any, WindowOrganizerWrapper>? {
        if (taskId <= 0) return null
        return try {
            val task = shizukuActivityManager()?.getRunningTasks(MAX_TASKS)
                ?.firstOrNull { it.taskId == taskId } ?: return null
            val token = ActivityManager.RunningTaskInfo::class.java.getField("token").get(task) ?: return null
            val organizer = shizukuWindowOrganizer() ?: return null
            Triple(task, token, organizer)
        } catch (e: Exception) {
            Log.w(TAG, "privileged: task $taskId not reachable", e)
            null
        }
    }

    private fun windowingMode(task: ActivityManager.RunningTaskInfo): Int = try {
        ActivityManager.RunningTaskInfo::class.java.getMethod("getWindowingMode").invoke(task) as Int
    } catch (_: Exception) {
        -1
    }

    /** The window's place on screen (TaskInfo.configuration.windowConfiguration, hidden). */
    private fun taskBounds(task: ActivityManager.RunningTaskInfo): Rect? = try {
        val configuration = TaskInfo::class.java.getField("configuration").get(task)!!
        val windowConfiguration = configuration.javaClass.getField("windowConfiguration").get(configuration)!!
        (windowConfiguration.javaClass.getMethod("getBounds").invoke(windowConfiguration) as Rect)
            .takeUnless { it.isEmpty }?.let { Rect(it) }
    } catch (_: Exception) {
        null
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
