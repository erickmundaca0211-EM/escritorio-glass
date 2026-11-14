package cu.axel.smartdock.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cu.axel.smartdock.R
import cu.axel.smartdock.dialogs.DockLayoutDialog
import cu.axel.smartdock.dialogs.DonateDialog
import cu.axel.smartdock.fragments.PreferencesFragment
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.Utils
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        var launchCount = sharedPreferences.getInt("launch_count", 0)
        if (launchCount != -1 && launchCount < 5)
            sharedPreferences.edit { putInt("launch_count", ++launchCount) }
        if (launchCount == 5) {
            DonateDialog(this)
        }
        // The pinned dock is an overlay drawn on top of this activity, so the last settings
        // (e.g. "Help and about" with the credits) could end up hidden behind it
        supportFragmentManager.registerFragmentLifecycleCallbacks(object :
            FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
            ) {
                if (f !is PreferenceFragmentCompat) return
                val dockHeight = sharedPreferences.getString("dock_height", "56")?.toIntOrNull() ?: 56
                f.listView.clipToPadding = false
                f.listView.updatePadding(bottom = Utils.dpToPx(this@MainActivity, dockHeight + 16))
            }
        }, true)
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, PreferencesFragment())
            .commit()
        if (!DeviceUtils.hasStoragePermission(this)) {
            DeviceUtils.requestStoragePermissions(this)
        }
        // Escritorio Glass is a desktop: it starts with that layout (the others stay in Apariencia)
        if (sharedPreferences.getInt("dock_layout", -1) == -1)
            DockLayoutDialog.applyLayout(sharedPreferences, DockLayoutDialog.LAYOUT_DESKTOP)
        if (savedInstanceState == null) guidePermissions()

        Shizuku.addBinderReceivedListener {
            if (Shizuku.pingBinder()) {
                Log.e(packageName, "ready")
            }
        }

        Shizuku.addBinderDeadListener {
            Log.e(packageName, "dead")
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_grant_permissions -> startActivity(WelcomeActivity.permissionsIntent(this))
        }
        return super.onOptionsItemSelected(item)
    }

    /** The first time, the welcome; later, only what the desktop mode still lacks, if anything. */
    private fun guidePermissions() {
        val canDrawOverApps = DeviceUtils.canDrawOverOtherApps(this)
        val desktopModeOn = DeviceUtils.isAccessibilityServiceEnabled(this)
        when {
            // Everything in place, also for whoever set it up before the welcome existed
            canDrawOverApps && desktopModeOn ->
                sharedPreferences.edit { putBoolean(WelcomeActivity.KEY_DONE, true) }

            !sharedPreferences.getBoolean(WelcomeActivity.KEY_DONE, false) ->
                startActivity(Intent(this, WelcomeActivity::class.java))

            // Desktop mode was turned off (power menu > "Salir del modo escritorio"): offer to come back
            canDrawOverApps && DeviceUtils.hasWriteSettingsPermission(this) -> showEnterDesktopDialog()
            else -> startActivity(WelcomeActivity.permissionsIntent(this))
        }
    }

    private fun showEnterDesktopDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.desktop_off_title)
            .setMessage(R.string.desktop_off_message)
            .setPositiveButton(R.string.desktop_enter) { _, _ -> DeviceUtils.enableService(this) }
            .setNegativeButton(R.string.desktop_not_now, null)
            .show()
    }
}
