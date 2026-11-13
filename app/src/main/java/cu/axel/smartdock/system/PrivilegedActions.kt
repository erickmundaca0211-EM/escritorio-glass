package cu.axel.smartdock.system

import android.Manifest
import android.app.ActivityManager
import android.app.TaskInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import cu.axel.smartdock.wrappers.ActivityManagerWrapper
import cu.axel.smartdock.wrappers.StatusBarWrapper
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
         * Send one window behind all the others, like minimizing it in Windows; a floating window is
         * hidden where it is instead (HyperOS keeps them above everything). Only from the user's click
         * on its taskbar button or Show desktop, only a task id the system itself listed; the next
         * click brings it back to the front. Hidden windows are shown again when desktop mode starts
         * or stops (Shizuku).
         */
        MINIMIZE_TASK,

        /**
         * Bring one window back to the front from its taskbar button, at once (the app's own request
         * can be held back for seconds after a minimize). Only a task id the system listed (Shizuku).
         */
        RESTORE_TASK,

        /**
         * Keep the system's pull-down shade and its quick settings closed while desktop mode runs:
         * the taskbar's notification center and quick settings take their place. It is tied to a
         * token of this process, so the system lifts it by itself if the app dies; desktop mode
         * lifts it when it stops (Shizuku).
         */
        BLOCK_SYSTEM_SHADE
    }

    /** Most windows the taskbar asks for at once. */
    private const val MAX_TASKS = 100

    // WindowConfiguration windowing mode and activity type (hidden constants)
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val ACTIVITY_TYPE_HOME = 2

    /** Our own state for these actions: only the ids of the floating windows we hid. */
    private const val STATE_FILE = "privileged_state"
    private const val KEY_HIDDEN_WINDOWS = "hidden_floating_windows"

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

        Action.LIST_TASKS, Action.CLOSE_TASK, Action.MINIMIZE_TASK, Action.RESTORE_TASK,
        Action.BLOCK_SYSTEM_SHADE -> ShizukuState.isUsable()
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

    private var statusBar: StatusBarWrapper? = null

    @Synchronized
    private fun shizukuStatusBar(): StatusBarWrapper? {
        statusBar?.let { if (it.isAlive()) return it }
        return try {
            StatusBarWrapper().also { statusBar = it }
        } catch (e: Exception) {
            Log.w(TAG, "privileged: Shizuku status bar unavailable", e)
            null
        }
    }

    /** Shizuku stopped: drop its binders (new ones are made when it starts again). */
    @Synchronized
    fun forgetShizuku() {
        activityManager = null
        windowOrganizer = null
        statusBar = null
    }

    // StatusBarManager flags (hidden constants): no pulling down the shade, no quick settings
    private const val DISABLE_EXPAND = 0x00010000
    private const val DISABLE2_QUICK_SETTINGS = 1

    /** Names our block to the status bar service; it lives and dies with this process. */
    private val shadeToken = Binder()

    /** Whether the system shade is blocked by us (only Shizuku can lift it again). */
    @Volatile
    var systemShadeBlocked = false
        private set

    fun setSystemShadeBlocked(context: Context, blocked: Boolean): Boolean {
        if (!isAllowed(context, Action.BLOCK_SYSTEM_SHADE)) return false
        val bar = shizukuStatusBar() ?: return false
        return try {
            if (blocked) bar.disable(DISABLE_EXPAND, DISABLE2_QUICK_SETTINGS, shadeToken, context.packageName)
            else bar.disable(0, 0, shadeToken, context.packageName)
            systemShadeBlocked = blocked
            Log.d(TAG, "privileged: system shade ${if (blocked) "blocked" else "released"}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "privileged: changing the system shade failed", e)
            false
        }
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
            val closed = shizukuActivityManager()?.removeTask(taskId) ?: false
            if (closed && hiddenWindows(context).remove(taskId)) saveHiddenWindows(context)
            closed
        } catch (e: Exception) {
            Log.w(TAG, "privileged: closing task $taskId failed", e)
            false
        }
    }

    /**
     * Floating windows we hid to minimize them (task ids). They are also saved: a hidden window stays
     * hidden if this app stops, so the next start shows it again ([showHiddenWindows]).
     */
    private val hiddenFloating = HashSet<Int>()
    private var hiddenLoaded = false

    @Synchronized
    private fun hiddenWindows(context: Context): MutableSet<Int> {
        if (!hiddenLoaded) {
            hiddenLoaded = true
            state(context).getStringSet(KEY_HIDDEN_WINDOWS, emptySet())!!
                .mapNotNullTo(hiddenFloating) { it.toIntOrNull() }
        }
        return hiddenFloating
    }

    /** Written in the background (on a busy phone a direct write held the bar for seconds). */
    private fun saveHiddenWindows(context: Context) {
        state(context).edit()
            .putStringSet(KEY_HIDDEN_WINDOWS, hiddenFloating.mapTo(HashSet()) { it.toString() }).apply()
    }

    private fun state(context: Context) = context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)

    fun minimizeTask(context: Context, taskId: Int): Boolean {
        if (!isAllowed(context, Action.MINIMIZE_TASK)) return false
        val (task, token, organizer) = listedTask(taskId) ?: return false
        return try {
            // HyperOS keeps floating windows above everything else: they are hidden where they are
            val floating = windowingMode(task) == WINDOWING_MODE_FREEFORM
            val hidden = hiddenWindows(context)
            if (floating) {
                hidden += taskId
                saveHiddenWindows(context)
                organizer.setHidden(token, true)
            } else {
                organizer.sendToBack(token)
                if (hidden.remove(taskId)) saveHiddenWindows(context)
            }
            Log.d(TAG, "privileged: task $taskId minimized (floating: $floating)")
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
            // A floating window only changes place in the window stack (shown if we hid it): opened
            // like from recents, HyperOS moves it elsewhere
            val floating = windowingMode(task) == WINDOWING_MODE_FREEFORM
            val hidden = hiddenWindows(context)
            if (floating) organizer.showInFront(token) else organizer.bringToFront(taskId, token)
            if (hidden.remove(taskId)) saveHiddenWindows(context)
            Log.d(TAG, "privileged: task $taskId restored (floating: $floating)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "privileged: restoring task $taskId failed", e)
            false
        }
    }

    /**
     * Shows again, where they are, the floating windows we hid: all of them (when desktop mode starts
     * or stops, so none stays hidden after it), or only [packageName]'s (that app is being opened: it
     * would come up hidden). Returns how many were shown.
     */
    fun showHiddenWindows(context: Context, packageName: String? = null): Int {
        if (!isAllowed(context, Action.RESTORE_TASK)) return 0
        val hidden = hiddenWindows(context)
        if (hidden.isEmpty()) return 0
        return try {
            val organizer = shizukuWindowOrganizer() ?: return 0
            val tasks = shizukuActivityManager()?.getRunningTasks(MAX_TASKS) ?: return 0
            val shown = tasks.filter {
                it.taskId in hidden && (packageName == null || it.baseActivity?.packageName == packageName)
            }
            shown.forEach { organizer.setHidden(taskToken(it), false) }
            // Without a package, the ones not listed anymore were closed
            if (packageName == null) hidden.clear() else hidden -= shown.map { it.taskId }.toSet()
            saveHiddenWindows(context)
            Log.d(TAG, "privileged: ${shown.size} hidden windows shown again")
            shown.size
        } catch (e: Exception) {
            Log.w(TAG, "privileged: showing hidden windows failed", e)
            0
        }
    }

    /** Whether floating windows we hid are waiting to come back. */
    fun hasHiddenWindows(context: Context) = hiddenWindows(context).isNotEmpty()

    /**
     * Shows the hidden floating windows that something else brought in front of the home screen: their
     * app was opened from the HyperOS home, a notification or recents, and came up still hidden. Also
     * forgets the ones closed meanwhile. Returns how many were shown.
     */
    fun showRaisedWindows(context: Context): Int {
        if (!isAllowed(context, Action.RESTORE_TASK)) return 0
        val hidden = hiddenWindows(context)
        if (hidden.isEmpty()) return 0
        return try {
            val organizer = shizukuWindowOrganizer() ?: return 0
            // Front one first; hiding a window sends it behind the home screen
            val rootTasks = organizer.rootTasks(Display.DEFAULT_DISPLAY) ?: return 0
            val home = rootTasks.indexOfFirst { activityType(it) == ACTIVITY_TYPE_HOME }
            if (home < 0) return 0
            val raised = rootTasks.take(home).filter { it.taskId in hidden }
            raised.forEach { organizer.showInFront(taskToken(it)) }
            val listed = rootTasks.flatMap { leafTaskIds(it).asList() }.toSet()
            val shown = raised.map { it.taskId }.toSet()
            if (hidden.removeAll { it in shown || it !in listed }) saveHiddenWindows(context)
            if (raised.isNotEmpty()) Log.d(TAG, "privileged: ${raised.size} hidden windows opened elsewhere, shown")
            raised.size
        } catch (e: Exception) {
            Log.w(TAG, "privileged: looking at hidden windows failed", e)
            0
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
            val organizer = shizukuWindowOrganizer() ?: return null
            Triple(task, taskToken(task), organizer)
        } catch (e: Exception) {
            Log.w(TAG, "privileged: task $taskId not reachable", e)
            null
        }
    }

    /** The window token of a task the system listed (TaskInfo.token, hidden). */
    private fun taskToken(task: TaskInfo): Any = TaskInfo::class.java.getField("token").get(task)!!

    /** The windows inside a top-level one (RootTaskInfo.childTaskIds, hidden): itself for a floating window. */
    private fun leafTaskIds(rootTask: TaskInfo): IntArray = try {
        rootTask.javaClass.getField("childTaskIds").get(rootTask) as IntArray
    } catch (_: Exception) {
        intArrayOf(rootTask.taskId)
    }

    private fun activityType(task: TaskInfo): Int = try {
        TaskInfo::class.java.getMethod("getActivityType").invoke(task) as Int
    } catch (_: Exception) {
        -1
    }

    private fun windowingMode(task: ActivityManager.RunningTaskInfo): Int = try {
        ActivityManager.RunningTaskInfo::class.java.getMethod("getWindowingMode").invoke(task) as Int
    } catch (_: Exception) {
        -1
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
