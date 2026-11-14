package cu.axel.smartdock.activities

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cu.axel.smartdock.R
import cu.axel.smartdock.glass.GlassSurface
import cu.axel.smartdock.services.NotificationService
import cu.axel.smartdock.services.taskbarClearancePx
import cu.axel.smartdock.system.ShizukuState
import cu.axel.smartdock.utils.CalendarUtils
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.Utils
import rikka.shizuku.Shizuku

/**
 * First-run welcome (Fase 6, step 8): a glass card over the wallpaper that walks through what
 * Escritorio Glass needs, one step at a time: display over other apps, restricted settings (only for an
 * app installed from an APK file), the accessibility service that is the desktop mode itself, and the
 * optional extras. Every step says whether it is already done; "Administrar permisos" opens it again at
 * the first thing still missing.
 */
class WelcomeActivity : AppCompatActivity() {
    private enum class Step { WELCOME, OVERLAY, RESTRICTED, ACCESSIBILITY, EXTRAS, DONE }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var steps: List<Step>
    private var index = 0

    private lateinit var icon: ImageView
    private lateinit var stepLabel: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var state: TextView
    private lateinit var extras: LinearLayout
    private lateinit var back: TextView
    private lateinit var skip: TextView
    private lateinit var action: TextView

    private lateinit var root: View

    /** Bottom system bar inset, from the last insets pass. */
    private var systemBottom = 0

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { show(index) }

    // Shizuku may start, stop or answer while this is open; its callbacks can come on other threads
    private val shizukuStarted = Shizuku.OnBinderReceivedListener { handler.post(refresh) }
    private val shizukuStopped = Shizuku.OnBinderDeadListener { handler.post(refresh) }
    private val shizukuAnswered = Shizuku.OnRequestPermissionResultListener { _, _ -> handler.post(refresh) }

    private val calendarRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { show(index) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_welcome)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        root = findViewById(R.id.welcome_root)
        val card = findViewById<View>(R.id.welcome_card)
        icon = findViewById(R.id.welcome_icon)
        stepLabel = findViewById(R.id.welcome_step)
        title = findViewById(R.id.welcome_title)
        body = findViewById(R.id.welcome_body)
        state = findViewById(R.id.welcome_state)
        extras = findViewById(R.id.welcome_extras)
        back = findViewById(R.id.welcome_back)
        skip = findViewById(R.id.welcome_skip)
        action = findViewById(R.id.welcome_action)

        ColorUtils.applyMainColor(this, sharedPreferences, card, GlassSurface.PANEL)
        action.background = GradientDrawable().apply {
            cornerRadius = Utils.dpToPx(this@WelcomeActivity, 19).toFloat()
            setColor(ColorUtils.getAccentColor(sharedPreferences))
        }
        // At most 560 dp wide, with room around it when the phone is held upright
        val margin = Utils.dpToPx(this, 16)
        card.layoutParams = card.layoutParams.apply {
            width = minOf(Utils.dpToPx(this@WelcomeActivity, 560), resources.displayMetrics.widthPixels - 2 * margin)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            systemBottom = bars.bottom
            keepClearOfTaskbar()
            insets
        }

        steps = buildList {
            add(Step.WELCOME)
            add(Step.OVERLAY)
            // Only an app installed from an APK file has its accessibility locked away
            if (accessibilityLocked()) add(Step.RESTRICTED)
            add(Step.ACCESSIBILITY)
            add(Step.EXTRAS)
            add(Step.DONE)
        }
        val savedStep = savedInstanceState?.getString(KEY_STEP)
        index = steps.indexOfFirst { it.name == savedStep }.takeIf { it >= 0 } ?: firstStep()

        back.setOnClickListener { show(index - 1) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (index > 0) show(index - 1) else finish()
            }
        })
        Shizuku.addBinderReceivedListener(shizukuStarted)
        Shizuku.addBinderDeadListener(shizukuStopped)
        Shizuku.addRequestPermissionResultListener(shizukuAnswered)
    }

    override fun onResume() {
        super.onResume()
        // Back from a system screen: show what changed there
        show(index)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STEP, steps[index].name)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        Shizuku.removeBinderReceivedListener(shizukuStarted)
        Shizuku.removeBinderDeadListener(shizukuStopped)
        Shizuku.removeRequestPermissionResultListener(shizukuAnswered)
        super.onDestroy()
    }

    /** Where to begin: the welcome itself the first time, else the first thing still missing. */
    private fun firstStep(): Int {
        if (!intent.getBooleanExtra(EXTRA_PERMISSIONS, false)) return 0
        val missing = steps.indexOfFirst { isRequired(it) && !isDone(it) }
        return if (missing >= 0) missing else steps.indexOf(Step.EXTRAS)
    }

    private fun isRequired(step: Step) =
        step == Step.OVERLAY || step == Step.RESTRICTED || step == Step.ACCESSIBILITY

    private fun isDone(step: Step) = when (step) {
        Step.OVERLAY -> Settings.canDrawOverlays(this)
        // Once the desktop mode is on, the lock no longer matters
        Step.RESTRICTED -> !accessibilityLocked() || DeviceUtils.isAccessibilityServiceEnabled(this)
        Step.ACCESSIBILITY -> DeviceUtils.isAccessibilityServiceEnabled(this)
        else -> true
    }

    /**
     * The usage access itself: "Recomendado" in Start reads it directly, so Shizuku being on does not
     * count here (DeviceUtils.hasRecentAppsPermission does count it, for the taskbar).
     */
    private fun hasUsageAccess(): Boolean = try {
        getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, applicationInfo.uid, packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    /** Android 13+ keeps accessibility from an app installed from an APK file until the user allows it. */
    private fun accessibilityLocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
                OP_ACCESS_RESTRICTED_SETTINGS, applicationInfo.uid, packageName
            ) == AppOpsManager.MODE_ERRORED
        } catch (_: Exception) {
            false
        }
    }

    /** Once the desktop mode is on, its floating taskbar covers the bottom: the card stays above it. */
    private fun keepClearOfTaskbar() {
        val taskbar = if (DeviceUtils.isAccessibilityServiceEnabled(this))
            taskbarClearancePx(this, sharedPreferences) else 0
        root.updatePadding(bottom = maxOf(systemBottom, taskbar))
    }

    private fun show(position: Int) {
        index = position.coerceIn(0, steps.lastIndex)
        keepClearOfTaskbar()
        val step = steps[index]
        stepLabel.text = getString(R.string.welcome_step, index + 1, steps.size)
        back.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
        extras.isVisible = step == Step.EXTRAS
        state.isVisible = false
        skip.isVisible = false
        when (step) {
            Step.WELCOME -> {
                content(R.drawable.ic_eg_start, R.string.welcome_title, R.string.welcome_body)
                primary(R.string.welcome_start) { show(index + 1) }
            }

            Step.OVERLAY -> required(
                step, R.drawable.ic_overlay, R.string.welcome_overlay_title,
                R.string.welcome_overlay_body, R.string.welcome_allow
            ) {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            }

            Step.RESTRICTED -> required(
                step, R.drawable.ic_lock, R.string.welcome_restricted_title,
                R.string.welcome_restricted_body, R.string.welcome_open_app_info
            ) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            }

            Step.ACCESSIBILITY -> {
                required(
                    step, R.drawable.ic_accessibility, R.string.welcome_accessibility_title,
                    R.string.welcome_accessibility_body, R.string.welcome_activate
                ) { turnOnDesktopMode() }
                // The service draws the taskbar: without that permission it cannot start
                if (!isDone(step) && !isDone(Step.OVERLAY)) {
                    showState(R.string.welcome_needs_overlay, R.drawable.ic_alert, warning = true)
                    primary(R.string.welcome_go_to_step) { show(steps.indexOf(Step.OVERLAY)) }
                }
            }

            Step.EXTRAS -> {
                content(R.drawable.ic_eg_settings, R.string.welcome_extras_title, R.string.welcome_extras_body)
                fillExtras()
                primary(R.string.welcome_next) { show(index + 1) }
            }

            Step.DONE -> {
                val missing = steps.firstOrNull { isRequired(it) && !isDone(it) }
                if (missing == null) {
                    content(R.drawable.ic_granted, R.string.welcome_done_title, R.string.welcome_done_body)
                    primary(R.string.welcome_go_desktop) { finishWelcome(toDesktop = true) }
                } else {
                    content(R.drawable.ic_alert, R.string.welcome_missing_title, R.string.welcome_missing_body)
                    primary(R.string.welcome_go_to_step) { show(steps.indexOf(missing)) }
                    secondary(R.string.welcome_exit) { finishWelcome(toDesktop = false) }
                }
            }
        }
    }

    /** A step the desktop cannot work without: its button, or "Listo" once it is done. */
    private fun required(step: Step, iconRes: Int, titleRes: Int, bodyRes: Int, actionRes: Int, grant: () -> Unit) {
        content(iconRes, titleRes, bodyRes)
        if (isDone(step)) {
            showState(R.string.welcome_ready, R.drawable.ic_granted, warning = false)
            primary(R.string.welcome_next) { show(index + 1) }
        } else {
            primary(actionRes, grant)
            secondary(R.string.welcome_skip) { show(index + 1) }
        }
    }

    private fun content(iconRes: Int, titleRes: Int, bodyRes: Int) {
        icon.setImageResource(iconRes)
        title.setText(titleRes)
        body.setText(bodyRes)
    }

    private fun primary(textRes: Int, onClick: () -> Unit) {
        action.setText(textRes)
        action.setOnClickListener { onClick() }
    }

    private fun secondary(textRes: Int, onClick: () -> Unit) {
        skip.isVisible = true
        skip.setText(textRes)
        skip.setOnClickListener { onClick() }
    }

    private fun showState(textRes: Int, iconRes: Int, warning: Boolean) {
        state.isVisible = true
        state.setText(textRes)
        state.setCompoundDrawablesRelative(smallIcon(iconRes, warning), null, null, null)
    }

    /** An 18 dp icon in the accent colour, or in the warning colour. */
    private fun smallIcon(iconRes: Int, warning: Boolean) =
        AppCompatResources.getDrawable(this, iconRes)!!.mutate().apply {
            val size = Utils.dpToPx(this@WelcomeActivity, 18)
            setBounds(0, 0, size, size)
            setTint(
                if (warning) ContextCompat.getColor(this@WelcomeActivity, R.color.glass_warning)
                else ColorUtils.getAccentColor(sharedPreferences)
            )
        }

    private fun turnOnDesktopMode() {
        if (DeviceUtils.hasWriteSettingsPermission(this)) {
            DeviceUtils.enableService(this)
            // The service connects in a moment
            handler.postDelayed(refresh, SERVICE_START_MS)
            return
        }
        // The page of a single service is for the system only: the list, where the user finds it
        open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, R.string.welcome_accessibility_toast, Toast.LENGTH_LONG).show()
    }

    // ---- Optional extras

    private fun fillExtras() {
        extras.removeAllViews()
        addExtra(
            R.drawable.ic_eg_bell, R.string.welcome_notifications, R.string.welcome_notifications_desc,
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName),
            R.string.welcome_allow
        ) { openNotificationAccess() }
        addExtra(
            R.drawable.ic_usage_stats, R.string.welcome_usage, R.string.welcome_usage_desc,
            hasUsageAccess(), R.string.welcome_allow
        ) {
            if (!open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri())))
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addExtra(
            R.drawable.ic_eg_calendar, R.string.welcome_calendar, R.string.welcome_calendar_desc,
            CalendarUtils.hasPermission(this), R.string.welcome_allow
        ) { calendarRequest.launch(Manifest.permission.READ_CALENDAR) }
        val shizuku = ShizukuState.status(this)
        addExtra(
            R.drawable.ic_eg_key, R.string.welcome_shizuku, R.string.welcome_shizuku_desc,
            shizuku == ShizukuState.Status.READY,
            when (shizuku) {
                ShizukuState.Status.NOT_INSTALLED -> R.string.welcome_install
                ShizukuState.Status.STOPPED -> R.string.welcome_open
                else -> R.string.welcome_allow
            }
        ) {
            when (shizuku) {
                ShizukuState.Status.NOT_INSTALLED -> ShizukuState.openStorePage(this)
                ShizukuState.Status.STOPPED -> ShizukuState.openShizuku(this)
                else -> ShizukuState.requestPermission()
            }
        }
        addExtra(
            R.drawable.ic_adb, R.string.welcome_adb, R.string.welcome_adb_desc,
            DeviceUtils.hasWriteSettingsPermission(this), R.string.welcome_show_command
        ) { showAdbCommand() }
    }

    private fun addExtra(
        iconRes: Int, nameRes: Int, descRes: Int, granted: Boolean, actionRes: Int, onClick: () -> Unit
    ) {
        val row = layoutInflater.inflate(R.layout.welcome_extra_row, extras, false)
        row.findViewById<ImageView>(R.id.extra_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.extra_name).setText(nameRes)
        row.findViewById<TextView>(R.id.extra_desc).setText(descRes)
        val button = row.findViewById<TextView>(R.id.extra_action)
        if (granted) {
            button.setText(R.string.welcome_ready)
            button.setCompoundDrawablesRelative(smallIcon(R.drawable.ic_granted, warning = false), null, null, null)
        } else {
            button.setText(actionRes)
            button.setBackgroundResource(R.drawable.start_pill_bg)
            button.setOnClickListener { onClick() }
        }
        extras.addView(row)
    }

    private fun openNotificationAccess() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(this, NotificationService::class.java).flattenToString()
        )
        if (!open(detail)) open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /**
     * How a computer grants WRITE_SECURE_SETTINGS (CLAUDE.md, "Comandos"): the steps and the command on
     * screen to type it there, since the phone's clipboard does not reach the PC; "Copiar" still serves
     * whoever syncs the clipboard.
     */
    private fun showAdbCommand() {
        val command = "adb shell pm grant $packageName ${Manifest.permission.WRITE_SECURE_SETTINGS}"
        val side = Utils.dpToPx(this, 24)
        val commandView = TextView(this).apply {
            text = command
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(side, Utils.dpToPx(this@WelcomeActivity, 4), side, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.welcome_adb)
            .setMessage(R.string.welcome_adb_steps)
            .setView(commandView)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.welcome_copy_command) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.welcome_adb), command))
                Toast.makeText(this, R.string.welcome_command_copied, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun finishWelcome(toDesktop: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_DONE, true) }
        if (toDesktop)
            open(Intent(this, DesktopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        /** The welcome was finished; until then MainActivity shows it while something required is missing. */
        const val KEY_DONE = "welcome_done"
        private const val EXTRA_PERMISSIONS = "permissions"
        private const val KEY_STEP = "step"
        private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"
        private const val SERVICE_START_MS = 700L

        /** The welcome at the first permission still missing (menu "Administrar permisos"). */
        fun permissionsIntent(context: Context): Intent =
            Intent(context, WelcomeActivity::class.java).putExtra(EXTRA_PERMISSIONS, true)
    }
}
