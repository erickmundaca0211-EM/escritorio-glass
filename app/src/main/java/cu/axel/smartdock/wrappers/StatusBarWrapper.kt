package cu.axel.smartdock.wrappers

import android.os.IBinder
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * The system's status bar service through Shizuku's binder, used only by PrivilegedActions to keep
 * the pull-down shade and its quick settings closed while desktop mode runs. Its interface is hidden,
 * so it is reached by reflection; hidden-API access is opened by RestrictionBypass at process start.
 */
class StatusBarWrapper {
    private val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("statusbar"))
    private val service: Any = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
        .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    private val disableMethod: Method = service.javaClass.getMethod(
        "disable", Int::class.javaPrimitiveType, IBinder::class.java, String::class.java
    )
    private val disable2Method: Method = service.javaClass.getMethod(
        "disable2", Int::class.javaPrimitiveType, IBinder::class.java, String::class.java
    )

    fun isAlive() = binder.isBinderAlive

    /**
     * Sets the parts of the system UI that [token] keeps disabled; 0 and 0 give them back. The
     * system forgets them by itself when the process that holds [token] dies.
     */
    fun disable(what: Int, what2: Int, token: IBinder, packageName: String) {
        disableMethod.invoke(service, what, token, packageName)
        disable2Method.invoke(service, what2, token, packageName)
    }
}
