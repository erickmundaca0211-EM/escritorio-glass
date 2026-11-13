package cu.axel.smartdock.wrappers

import android.graphics.Rect
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
    private val setWindowingModeMethod: Method =
        transactionClass.getMethod("setWindowingMode", tokenClass, Int::class.javaPrimitiveType)
    private val setBoundsMethod: Method = transactionClass.getMethod("setBounds", tokenClass, Rect::class.java)
    private val applyTransaction: Method
    private val taskManager: Any
    private val startFromRecents: Method

    init {
        // The activity task manager hands out the organizer; both are called as Shizuku's shell user
        taskManager = Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task")))!!
        startFromRecents = taskManager.javaClass
            .getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, Bundle::class.java)
        val organizer = taskManager.javaClass.getMethod("getWindowOrganizerController").invoke(taskManager)!!
        val organizerBinder = organizer.javaClass.getMethod("asBinder").invoke(organizer) as IBinder
        binder = ShizukuBinderWrapper(organizerBinder)
        controller = Class.forName("android.window.IWindowOrganizerController\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        applyTransaction = controller.javaClass.getMethod("applyTransaction", transactionClass)
    }

    fun isAlive() = binder.isBinderAlive

    /**
     * Sends the window whose [token] (TaskInfo.token) is given behind the desktop. HyperOS keeps
     * floating windows above everything else, so [fromFloating] ones become full-screen first (a plain
     * app in recents if the desktop stops before they come back).
     */
    fun sendToBack(token: Any, fromFloating: Boolean) {
        val transaction = transactionClass.getConstructor().newInstance()
        if (fromFloating) setWindowingModeMethod.invoke(transaction, token, WINDOWING_MODE_FULLSCREEN)
        reorderMethod.invoke(transaction, token, false)
        applyTransaction.invoke(controller, transaction)
    }

    /**
     * Brings the window [taskId] in front of the others, as a floating window again at
     * [floatingBounds] when it was one. It is opened like from recents, as Shizuku's shell user: at
     * once (not held back like an app's own request after a minimize) and showing the window's last
     * picture while the app redraws. If that is refused the window stack is reordered directly.
     */
    fun bringToFront(taskId: Int, token: Any, floatingBounds: Rect?) {
        if (floatingBounds != null) {
            val transaction = transactionClass.getConstructor().newInstance()
            setWindowingModeMethod.invoke(transaction, token, WINDOWING_MODE_FREEFORM)
            setBoundsMethod.invoke(transaction, token, floatingBounds)
            applyTransaction.invoke(controller, transaction)
        }
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
        // WindowConfiguration constants (hidden)
        const val WINDOWING_MODE_FULLSCREEN = 1
        const val WINDOWING_MODE_FREEFORM = 5

        // ActivityManager start results below 0 are failures
        const val START_REFUSED = -1
    }
}
