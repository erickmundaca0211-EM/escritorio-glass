package cu.axel.smartdock.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku

/**
 * Where Shizuku stands for Escritorio Glass (docs/PLAN.md): installed, started and allowed. Shizuku is
 * a separate app the user installs and starts; Escritorio Glass never starts it on its own. The
 * privileged actions that use it live in [PrivilegedActions].
 */
object ShizukuState {
    const val PACKAGE = "moe.shizuku.privileged.api"

    /** Free and open source; the official page on Play Store, or its releases on GitHub. */
    private const val STORE_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"
    private const val RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases"
    private const val PLAY_STORE = "com.android.vending"
    private const val PERMISSION_REQUEST = 4711

    enum class Status { NOT_INSTALLED, STOPPED, NO_PERMISSION, READY }

    fun status(context: Context): Status = when {
        !isInstalled(context) -> Status.NOT_INSTALLED
        !isRunning() -> Status.STOPPED
        !isGranted() -> Status.NO_PERMISSION
        else -> Status.READY
    }

    fun isReady(context: Context) = status(context) == Status.READY

    private fun isInstalled(context: Context) = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isRunning() = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun isGranted() = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Shows Shizuku's own dialog asking whether Escritorio Glass may use it. */
    fun requestPermission() {
        if (isRunning()) try {
            Shizuku.requestPermission(PERMISSION_REQUEST)
        } catch (_: Throwable) {
        }
    }

    /** Opens Shizuku, where the user starts it (wireless debugging or a computer). */
    fun openShizuku(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Opens Shizuku's page on Play Store (the store downloads it, not Escritorio Glass). */
    fun openStorePage(context: Context) {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse(STORE_URL))
            .setPackage(PLAY_STORE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(store)
        } catch (_: ActivityNotFoundException) {
            // No Play Store: the official releases page in the browser
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
