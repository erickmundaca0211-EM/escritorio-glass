package cu.axel.smartdock.wrappers

import android.app.TaskInfo
import android.os.Bundle
import android.os.IBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * The system's window organizer through Shizuku's binder, used only by PrivilegedActions to send a
 * window behind the others (minimize) or bring it back to the front. Its classes are hidden, so they
 * are reached by reflection; hidden-API access is opened by RestrictionBypass at process start.
 */
class WindowOrganizerWrapper {
    private val binder: ShizukuBinderWrapper
    private val controller: Any
    private val transactionClass: Class<*> = Class.forName("android.window.WindowContainerTransaction")
    private val tokenClass: Class<*> = Class.forName("android.window.WindowContainerToken")
    private val reorderMethod: Method =
        transactionClass.getMethod("reorder", tokenClass, Boolean::class.javaPrimitiveType)
    private val setHiddenMethod: Method =
        transactionClass.getMethod("setHidden", tokenClass, Boolean::class.javaPrimitiveType)
    private val applyTransaction: Method
    private val taskManager: Any
    private val startFromRecents: Method
    private val allRootTasks: Method?

    init {
        // The activity task manager hands out the organizer; both are called as Shizuku's shell user
        taskManager = Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task")))!!
        startFromRecents = taskManager.javaClass
            .getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, Bundle::class.java)
        allRootTasks = try {
            taskManager.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
            null
        }
        val organizer = taskManager.javaClass.getMethod("getWindowOrganizerController").invoke(taskManager)!!
        val organizerBinder = organizer.javaClass.getMethod("asBinder").invoke(organizer) as IBinder
        binder = ShizukuBinderWrapper(organizerBinder)
        controller = Class.forName("android.window.IWindowOrganizerController\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        applyTransaction = controller.javaClass.getMethod("applyTransaction", transactionClass)
    }

    fun isAlive() = binder.isBinderAlive

    /** The display's top-level windows (root tasks), the front one first; null if it can't be read. */
    fun rootTasks(displayId: Int): List<TaskInfo>? =
        (allRootTasks?.invoke(taskManager, displayId) as? List<*>)?.filterIsInstance<TaskInfo>()

    /** Sends the window whose [token] (TaskInfo.token) is given behind the desktop. */
    fun sendToBack(token: Any) {
        val transaction = transactionClass.getConstructor().newInstance()
        reorderMethod.invoke(transaction, token, false)
        applyTransaction.invoke(controller, transaction)
    }

    /**
     * Hides the window [token] where it is, or shows it again. HyperOS keeps floating windows above
     * everything else, so they can't go behind the desktop; hidden, they stay the same floating
     * windows. Making them full-screen and floating again broke them: HyperOS only lets the user move
     * the floating windows it made itself. A hidden window stays hidden until it is shown again or
     * closed, also if this app stops (PrivilegedActions keeps their ids to show them again).
     */
    fun setHidden(token: Any, hidden: Boolean) {
        val transaction = transactionClass.getConstructor().newInstance()
        setHiddenMethod.invoke(transaction, token, hidden)
        applyTransaction.invoke(controller, transaction)
    }

    /**
     * Brings the floating window [token] in front of the others where it is, also if it was hidden:
     * only the window stack changes. Opened like from recents instead, HyperOS moves it elsewhere.
     */
    fun showInFront(token: Any) {
        val transaction = transactionClass.getConstructor().newInstance()
        setHiddenMethod.invoke(transaction, token, false)
        reorderMethod.invoke(transaction, token, true)
        applyTransaction.invoke(controller, transaction)
    }

    /**
     * Brings the window [taskId] in front of the others. It is opened like from recents, as
     * Shizuku's shell user: at once (not held back like an app's own request after a minimize) and
     * showing the window's last picture while the app redraws. If that is refused the window stack
     * is reordered directly.
     */
    fun bringToFront(taskId: Int, token: Any) {
        val result = try {
            startFromRecents.invoke(taskManager, taskId, null) as Int
        } catch (_: Exception) {
            START_REFUSED
        }
        if (result < 0) {
            val transaction = transactionClass.getConstructor().newInstance()
            reorderMethod.invoke(transaction, token, true)
            applyTransaction.invoke(controller, transaction)
        }
    }

    private companion object {
        // ActivityManager start results below 0 are failures
        const val START_REFUSED = -1
    }
}
