package cu.axel.smartdock.services

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.os.RemoteException
import android.graphics.Bitmap
import android.view.inputmethod.EditorInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.addTextChangedListener
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import cu.axel.smartdock.INotificationCallback
import cu.axel.smartdock.INotificationServiceBridge
import cu.axel.smartdock.R
import cu.axel.smartdock.activities.LAUNCHER_ACTION
import cu.axel.smartdock.activities.LAUNCHER_RESUMED
import cu.axel.smartdock.activities.MainActivity
import cu.axel.smartdock.adapters.AppActionsAdapter
import cu.axel.smartdock.adapters.AppListAdapter
import cu.axel.smartdock.adapters.PinnedAppsAdapter
import cu.axel.smartdock.adapters.RecentAppsAdapter
import cu.axel.smartdock.utils.StartMenuUtils
import cu.axel.smartdock.components.CalendarPanel
import cu.axel.smartdock.components.NotificationCenter
import cu.axel.smartdock.components.QuickSettingsPanel
import cu.axel.smartdock.adapters.AppAdapter
import cu.axel.smartdock.adapters.AppAdapter.OnAppClickListener
import cu.axel.smartdock.adapters.AppShortcutAdapter
import cu.axel.smartdock.adapters.AppTaskAdapter
import cu.axel.smartdock.adapters.DisplaysAdapter
import cu.axel.smartdock.adapters.DockAppAdapter
import cu.axel.smartdock.adapters.DockAppAdapter.OnDockAppClickListener
import cu.axel.smartdock.adapters.NotificationAdapter
import cu.axel.smartdock.adapters.NotificationAdapter.OnNotificationClickListener
import cu.axel.smartdock.components.NotificationLayout
import cu.axel.smartdock.db.DBHelper
import cu.axel.smartdock.dialogs.DockDialog
import cu.axel.smartdock.dialogs.DockLayoutDialog
import cu.axel.smartdock.dialogs.NotificationPermissionDialog
import cu.axel.smartdock.models.Action
import cu.axel.smartdock.models.App
import cu.axel.smartdock.models.AppTask
import cu.axel.smartdock.models.DockApp
import cu.axel.smartdock.models.WINDOWING_MODE_FREEFORM
import cu.axel.smartdock.preferences.NAV_LONG_ACTIONS
import cu.axel.smartdock.receivers.BatteryStatsReceiver
import cu.axel.smartdock.receivers.SoundEventsReceiver
import cu.axel.smartdock.utils.AppUtils
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.ColorUtils.getMainColors
import cu.axel.smartdock.glass.GlassController
import cu.axel.smartdock.glass.GlassSurface
import cu.axel.smartdock.utils.CalendarUtils
import cu.axel.smartdock.system.DesktopScale
import android.provider.CalendarContract
import android.text.format.DateFormat
import java.util.Locale
import kotlin.math.max
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import cu.axel.smartdock.utils.DeepShortcutManager
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.IconPackUtils
import cu.axel.smartdock.utils.NotificationIntents
import cu.axel.smartdock.utils.OnSwipeListener
import cu.axel.smartdock.utils.PanelAnimator
import cu.axel.smartdock.utils.Utils
import cu.axel.smartdock.widgets.HoverInterceptorLayout
import cu.axel.smartdock.wrappers.ActivityManagerWrapper
import cu.axel.smartdock.wrappers.BluetoothManagerWrapper
import cu.axel.smartdock.wrappers.WifiManagerWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.Collator

const val DOCK_SERVICE_CONNECTED = "service_connected"
const val ACTION_BIND_NOTIFICATION_SERVICE = "bind_notification_service"
const val ACTION_LAUNCH_APP = "launch_app"
const val DESKTOP_APP_PINNED = "desktop_app_pinned"
const val DOCK_SERVICE_ACTION = "dock_service_action"

/** Escritorio Glass taskbar geometry (docs/DISENO.md). */
private const val TASKBAR_MARGIN_BOTTOM_DP = 8
private const val TASKBAR_ITEM_WIDTH_DP = 46 // 44 dp button + 2 dp gap, as in the mockup
private const val KEY_AUTOHIDE_FULLSCREEN = "autohide_fullscreen"

// Escritorio Glass Start menu (docs/DISENO.md, "Menú Inicio")
private const val START_MENU_WIDTH_DP = 560
private const val START_MENU_HEIGHT_DP = 434
private const val START_MENU_GAP_DP = 12 // above the taskbar
private const val START_MENU_COLUMNS = 6
private const val START_MENU_FIXED_DP = 228 // menu height without the pinned grid
private const val START_MENU_MIN_GRID_DP = 70 // one row of pinned apps
private const val START_RECENT_DEFAULT = 2
private const val START_RECENT_MAX = 6

// Quick settings and notification center (docs/DISENO.md)
private const val QUICK_SETTINGS_WIDTH_DP = 372
private const val NOTIFICATION_CENTER_WIDTH_DP = 340
private const val PANEL_SIDE_MARGIN_DP = 10
private const val TASKBAR_AUTOHIDE_DELAY_MS = 1000L // wait before hiding over a full-screen app (3 s felt slow)
private const val TASKBAR_HOVER_HIDE_DELAY_MS = 1000 // after the mouse leaves the bar
private const val FULLSCREEN_CHECK_DELAY_MS = 300L // let window changes settle

class DockService : AccessibilityService(), OnSharedPreferenceChangeListener, OnTouchListener,
    OnAppClickListener, OnDockAppClickListener, OnNotificationClickListener {

    // Escritorio Glass quick settings and notification center (docs/DISENO.md)
    private var quickSettings: QuickSettingsPanel? = null
    private var notificationCenter: NotificationCenter? = null
    private var notificationCenterWindow: View? = null
    private var notificationCenterVisible = false
    private var calendarPanel: CalendarPanel? = null
    private var calendarPanelWindow: View? = null
    private var calendarPanelVisible = false
    private var wifiManagerWrapper: WifiManagerWrapper? = null
    private var bluetoothManagerWrapper: BluetoothManagerWrapper? = null
    private var activityManagerWrapper: ActivityManagerWrapper? = null
    private var orientationValue: String = ""
    private var orientation = -1
    private var densityDpi = 0
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var activityManager: ActivityManager
    private lateinit var appsBtn: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var homeBtn: ImageView
    private lateinit var recentBtn: ImageView
    private lateinit var assistBtn: ImageView
    private lateinit var powerBtn: ImageView
    private lateinit var bluetoothBtn: ImageView
    private lateinit var wifiBtn: ImageView
    private lateinit var batteryBtn: TextView
    private lateinit var volumeBtn: ImageView
    private lateinit var pinBtn: ImageView
    private lateinit var notificationBtn: TextView
    private var topRightCorner: Button? = null
    private var bottomRightCorner: Button? = null
    private var dockHandle: Button? = null
    private var appMenu: LinearLayout? = null
    private var powerMenu: LinearLayout? = null
    private var quickSettingsPanel: LinearLayout? = null
    private lateinit var searchEntry: LinearLayout
    private lateinit var dockLayout: RelativeLayout
    private lateinit var windowManager: WindowManager
    private var appMenuVisible = false
    private var powerMenuVisible = false
    private var isPinned = false
    private var quickSettingsPanelVisible = false
    private var systemApp = false
    private var preferSecondaryDisplay = false
    private lateinit var dockLayoutParams: WindowManager.LayoutParams
    private lateinit var searchEt: EditText
    private lateinit var tasksGv: RecyclerView
    private lateinit var favoritesGv: RecyclerView
    private lateinit var appsGv: RecyclerView
    private lateinit var startHome: View
    private lateinit var startAll: View
    private lateinit var allAppsTitleTv: TextView
    private lateinit var pinnedEmptyTv: TextView
    private lateinit var pinnedSpacer: View
    private lateinit var noResultsTv: TextView
    private lateinit var recommendedSection: View
    private lateinit var recentLv: RecyclerView
    private lateinit var recentEmptyTv: TextView
    private lateinit var moreRecentBtn: TextView
    private lateinit var startUserNameTv: TextView
    private lateinit var startAvatarIv: ImageView
    private lateinit var startAvatarInitialTv: TextView
    private var installedApps: List<App> = emptyList()
    private var showAllRecent = false
    private var startSearching = false
    private lateinit var wifiManager: WifiManager
    private lateinit var batteryReceiver: BatteryStatsReceiver
    private lateinit var soundEventsReceiver: SoundEventsReceiver
    private lateinit var gestureDetector: GestureDetector
    private lateinit var db: DBHelper
    private lateinit var dockHandler: Handler

    // Escritorio Glass: the taskbar hides itself while a full-screen app is in front
    // (docs/PLAN.md, Fase 5). Only window types, bounds and the package of the app in front are
    // used, never what the windows show (CLAUDE.md).
    private var autoHidden = false
    private var autoHideSuppressed = false
    private val windowPackages = HashMap<Int, String>() // accessibility window id -> package
    private var homePackages = emptySet<String>()
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val fullscreenCheck = Runnable { checkFullscreenApp() }
    private val autoHideRunnable = Runnable { autoHideTaskbar() }
    private var dock: HoverInterceptorLayout? = null
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var pinnedApps: ArrayList<App>
    private lateinit var dateTv: TextClock
    private var maxApps = 0
    private var maxAppsLandscape = 0
    private lateinit var context: Context
    private lateinit var tasks: ArrayList<AppTask>
    private var lastUpdate: Long = 0
    private var dockHeight: Int = 0
    private lateinit var handleLayoutParams: WindowManager.LayoutParams
    private lateinit var launcherApps: LauncherApps
    private lateinit var displayManager: DisplayManager
    private lateinit var displayListener: DisplayManager.DisplayListener
    private lateinit var keyguardManager: KeyguardManager
    private var iconPackUtils: IconPackUtils? = null
    private var notificationBridge: INotificationServiceBridge? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var notificationLayout: NotificationLayout? = null
    private lateinit var statusArea: LinearLayout

    // Escritorio Glass taskbar (phase 2)
    private lateinit var searchBtn: ImageView
    private lateinit var trayOverflowBtn: ImageView
    private lateinit var dateBlock: LinearLayout
    private lateinit var dateMainTv: TextView
    private lateinit var nextEventTv: TextView
    private lateinit var centerGroup: LinearLayout
    private lateinit var navPanel: LinearLayout
    private lateinit var systemTray: LinearLayout
    private lateinit var notificationsArea: View
    private lateinit var clockArea: View
    private lateinit var dateLineTv: TextClock
    private var runningAppsCount = 0
    private var timeTickReceiver: BroadcastReceiver? = null
    override fun onCreate() {
        super.onCreate()
        db = DBHelper(this)
        activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        dockHandler = Handler(Looper.getMainLooper())
        if (sharedPreferences.getString("icon_pack", "")!!.isNotEmpty()) {
            iconPackUtils = IconPackUtils(this)
        }
        //Shizuku stuff
        initWrappers()
        Shizuku.addBinderReceivedListener {
            initWrappers()
        }
        Shizuku.addBinderDeadListener {
            destroyWrappers()
        }
    }

    private fun destroyWrappers() {
        activityManagerWrapper = null
        wifiManagerWrapper = null
        bluetoothManagerWrapper = null
    }

    private fun initWrappers() {
        if (DeviceUtils.hasShizukuPermission()) {
            try {
                activityManagerWrapper = ActivityManagerWrapper()
                wifiManagerWrapper = WifiManagerWrapper()
                bluetoothManagerWrapper = BluetoothManagerWrapper()
            } catch (e: Exception) {
                Log.e(packageName, e.toString())
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Utils.startupTime = System.currentTimeMillis()
        systemApp = AppUtils.isSystemApp(this, packageName)
        maxApps = sharedPreferences.getString("max_running_apps", "10")!!.toInt()
        maxAppsLandscape = sharedPreferences.getString("max_running_apps_landscape", "10")!!.toInt()
        preferSecondaryDisplay = sharedPreferences.getBoolean("prefer_last_display", false)
        // Desktop "app scale": smaller apps and windows while the desktop runs (restored in onDestroy)
        DesktopScale.apply(this, sharedPreferences)
        // Launchers count as "desktop", never as a full-screen app. Settings also declares a HOME
        // screen used only while the phone boots (FallbackHome, negative priority): skip it, or
        // Settings would never hide the bar
        homePackages = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).filter { it.priority >= 0 }.map { it.activityInfo.packageName }.toSet()
        // The first time, pin common apps to the Start menu so "Ancladas" isn't empty
        CoroutineScope(Dispatchers.Default).launch {
            StartMenuUtils.pinDefaultAppsOnce(this@DockService, sharedPreferences)
        }
        createViews()

        //Listen for launcher messages
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    LAUNCHER_RESUMED -> pinDock()
                    ACTION_LAUNCH_APP -> launchApp(
                        intent.getStringExtra("mode"),
                        intent.getStringExtra("app")!!
                    )
                }
            }
        }, IntentFilter(LAUNCHER_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        //Tell the launcher the service has connected
        sendBroadcast(
            Intent(DOCK_SERVICE_ACTION)
                .setPackage(packageName)
                .putExtra("action", DOCK_SERVICE_CONNECTED)
        )

        //Register receivers
        ContextCompat.registerReceiver(
            this, object : BroadcastReceiver() {
                override fun onReceive(p1: Context, intent: Intent) {
                    when (intent.getStringExtra("action")) {
                        NOTIFICATION_SERVICE_CONNECTED -> {
                            bindNotificationService()
                        }
                    }
                }
            }, IntentFilter(NOTIFICATION_SERVICE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        soundEventsReceiver = SoundEventsReceiver()
        val soundEventsFilter = IntentFilter()
        soundEventsFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        soundEventsFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        ContextCompat.registerReceiver(
            this,
            soundEventsReceiver,
            soundEventsFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, object : BroadcastReceiver() {
                override fun onReceive(p1: Context, intent: Intent) {
                    applyTheme()
                }
            }, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val filter = IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        filter.addDataScheme("package")
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                loadPinnedApps()
                updateRunningTasks()
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                Log.e(packageName, "Display added $displayId")
                if (preferSecondaryDisplay)
                    restartUI()
            }

            override fun onDisplayRemoved(displayId: Int) {
                Log.e(packageName, "Display removed $displayId")
                if (preferSecondaryDisplay) {
                    restartUI()
                }
            }

            override fun onDisplayChanged(displayId: Int) {
            }
        }

        displayManager.registerDisplayListener(displayListener, null)

        batteryReceiver = BatteryStatsReceiver(
            context,
            batteryBtn,
            sharedPreferences.getBoolean("show_battery_level$orientationValue", false)
        )
        updateBatteryBtn()
        ContextCompat.registerReceiver(
            this, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                updateWiFiStatus()
            }

        }, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                updateBluetoothStatus()
            }

        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

        timeTickReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                updateDateBlock()
            }
        }
        ContextCompat.registerReceiver(
            this, timeTickReceiver,
            IntentFilter(Intent.ACTION_TIME_TICK).apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI
            ).build(), wifiNetworkCallback
        )


        bindNotificationService()

        //Play startup sound
        DeviceUtils.playEventSound(this, "startup_sound")

        //Show the dock
        if (sharedPreferences.getBoolean("pin_dock", true))
            pinDock()
        else
            Toast.makeText(context, R.string.start_message, Toast.LENGTH_LONG).show()

    }

    private fun getAppActions(app: App): ArrayList<Action> {
        val actions = ArrayList<Action>()
        actions.add(startPinAction(app))
        if (DeepShortcutManager.hasHostPermission(context)) {
            if (!DeepShortcutManager.getShortcuts(app.packageName, context).isNullOrEmpty())
                actions.add(Action(R.drawable.ic_shortcuts, getString(R.string.shortcuts)))
        }
        actions.add(Action(R.drawable.ic_manage, getString(R.string.manage)))
        actions.add(Action(R.drawable.ic_launch_mode, getString(R.string.open_as)))
        if (DeviceUtils.getDisplays(this).size > 1)
            actions.add(Action(R.drawable.ic_add_to_desktop, getString(R.string.launch_in)))
        if (getPinActions(app).isNotEmpty())
            actions.add(Action(R.drawable.ic_pin, getString(R.string.add_to)))

        return actions
    }

    /** Pin or unpin an app on the Start menu ("Ancladas"). */
    private fun startPinAction(app: App) =
        if (AppUtils.isPinned(context, app, AppUtils.PINNED_LIST))
            Action(R.drawable.ic_remove_favorite, getString(R.string.start_unpin))
        else Action(R.drawable.ic_add_favorite, getString(R.string.start_pin))

    private fun toggleStartPin(app: App) {
        if (AppUtils.isPinned(context, app, AppUtils.PINNED_LIST))
            AppUtils.unpinApp(context, app.packageName, AppUtils.PINNED_LIST)
        else
            AppUtils.pinApp(context, app, AppUtils.PINNED_LIST)
        loadFavoriteApps()
    }

    private fun getDockAppActions(app: DockApp): ArrayList<Action> {
        val actions = ArrayList<Action>()
        if (AppUtils.isPinned(this, app, AppUtils.DOCK_PINNED_LIST)) {
            actions.add(Action(R.drawable.ic_unpin, getString(R.string.unpin)))
            actions.add(Action(R.drawable.ic_arrow_right, getString(R.string.move)))
        } else
            actions.add(Action(R.drawable.ic_pin, getString(R.string.pin)))
        actions.add(startPinAction(app))

        if (app.tasks.isNotEmpty() && app.tasks[0].id != -1 && DeviceUtils.hasShizukuPermission()) {
            if (app.tasks[0].windowingMode == WINDOWING_MODE_FREEFORM) {
                actions.add(Action(R.drawable.ic_launch_mode, getString(R.string.resize)))
                actions.add(Action(R.drawable.ic_snap_top, getString(R.string.snap)))
            }
            actions.add(Action(R.drawable.ic_close, getString(R.string.close)))
        }
        return actions
    }

    private fun getPinActions(app: App): ArrayList<Action> {
        val actions = ArrayList<Action>()
        if (!AppUtils.isPinned(context, app, AppUtils.DESKTOP_LIST))
            actions.add(Action(R.drawable.ic_add_to_desktop, getString(R.string.desktop)))
        if (!AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST))
            actions.add(Action(R.drawable.ic_pin, getString(R.string.dock)))

        return actions
    }

    override fun onDockAppClicked(app: DockApp, anchor: View) {
        val tasks = app.tasks
        if (tasks.size == 1) {
            val taskId = tasks[0].id
            if (taskId == -1)
                launchApp(null, app.packageName)
            else
                activityManager.moveTaskToFront(taskId, 0)
        } else if (tasks.size > 1) {
            val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
            val layoutParams = Utils.makeWindowParams(-2, -2, context, preferSecondaryDisplay)
            ColorUtils.applyMainColor(context, sharedPreferences, view, GlassSurface.POPUP)
            layoutParams.gravity = Gravity.BOTTOM or Gravity.START
            layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            layoutParams.y = Utils.dpToPx(context, 2) + dockHeight
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            layoutParams.x = location[0]
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE && view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
                false
            }
            val tasksLv = view.findViewById<ListView>(R.id.tasks_lv)
            tasksLv.adapter = AppTaskAdapter(context, tasks)
            tasksLv.setOnItemClickListener { adapterView, _, position, _ ->
                activityManager.moveTaskToFront(
                    (adapterView.getItemAtPosition(position) as AppTask).id, 0
                )
                windowManager.removeView(view)
            }
            windowManager.addView(view, layoutParams)
            PanelAnimator.show(view)
        } else launchApp(getDefaultLaunchMode(app.packageName), app.packageName)
        if (getDefaultLaunchMode(app.packageName) == "fullscreen") {
            if (isPinned && sharedPreferences.getBoolean("auto_unpin", true) && !autoHideEnabled()) {
                unpinDock()
            }
        } else {
            if (!isPinned && sharedPreferences.getBoolean("auto_pin", true)) {
                pinDock()
            }
        }
    }

    override fun onDockAppLongClicked(app: DockApp, view: View) {
        showDockAppContextMenu(app, view)
    }

    override fun onAppClicked(app: App, item: View) {
        if (app.packageName == "$packageName.calc") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("results", app.name))
            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        } else launchApp(null, app.packageName, null, app)
    }

    override fun onAppLongClicked(app: App, view: View) {
        if (app.packageName != "$packageName.calc") {
            showAppContextMenu(app, view)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            rememberForegroundApp(event)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            autoHideHandler.removeCallbacks(fullscreenCheck)
            autoHideHandler.postDelayed(fullscreenCheck, FULLSCREEN_CHECK_DELAY_MS)
        }
        if (!isPinned)
            return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (event.windowChanges.and(AccessibilityEvent.WINDOWS_CHANGE_REMOVED) == AccessibilityEvent.WINDOWS_CHANGE_REMOVED ||
                event.windowChanges.and(
                    AccessibilityEvent.WINDOWS_CHANGE_ADDED
                ) == AccessibilityEvent.WINDOWS_CHANGE_ADDED
            )
                updateRunningTasks()
        } else if (sharedPreferences.getBoolean(
                "custom_toasts",
                false
            ) && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.parcelableData !is Notification && event.text.isNotEmpty()
        ) {
            val text = event.text[0].toString()
            val app = event.packageName.toString()
            showToast(app, text)
        }
    }

    private fun showToast(app: String, text: String) {
        val layoutParams = Utils.makeWindowParams(-2, -2, context, preferSecondaryDisplay)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER
        layoutParams.y = dock!!.measuredHeight + Utils.dpToPx(context, 4)
        val toast = LayoutInflater.from(context).inflate(R.layout.toast, null)
        ColorUtils.applyMainColor(context, sharedPreferences, toast, GlassSurface.POPUP)
        val textTv = toast.findViewById<TextView>(R.id.toast_tv)
        val iconIv = toast.findViewById<ImageView>(R.id.toast_iv)
        textTv.text = text
        val notificationIcon = AppUtils.getAppIcon(context, app)
        iconIv.setImageDrawable(notificationIcon)
        ColorUtils.applyColor(iconIv, ColorUtils.getDrawableDominantColor(notificationIcon))
        toast.alpha = 0f
        toast.animate().alpha(1f).setDuration(250).interpolator = AccelerateDecelerateInterpolator()
        Handler(Looper.getMainLooper()).postDelayed({
            toast.animate().alpha(0f).setDuration(400)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        windowManager.removeView(toast)
                    }
                })
        }, 5000)
        windowManager.addView(toast, layoutParams)
    }

    override fun onInterrupt() {}

    //Handle keyboard shortcuts
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Esc closes the Start menu (keys are only used as shortcuts, never stored)
        if (appMenuVisible && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) hideAppMenu()
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.isAltPressed) {
                if (event.keyCode == KeyEvent.KEYCODE_L && sharedPreferences.getBoolean(
                        "enable_lock_desktop",
                        true
                    )
                )
                    lockScreen()
                else if (event.keyCode == KeyEvent.KEYCODE_P && sharedPreferences.getBoolean(
                        "enable_open_settings",
                        true
                    )
                )
                    launchApp(
                        null, null,
                        Intent(Settings.ACTION_SETTINGS)
                    )
                else if (event.keyCode == KeyEvent.KEYCODE_T && sharedPreferences.getBoolean(
                        "enable_open_terminal",
                        false
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_terminal", "com.termux")!!)
                else if (event.keyCode == KeyEvent.KEYCODE_Q && sharedPreferences.getBoolean(
                        "enable_expand_notifications",
                        true
                    )
                )
                    performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                else if (event.keyCode == KeyEvent.KEYCODE_W && sharedPreferences.getBoolean(
                        "enable_toggle_pin",
                        true
                    )
                )
                    togglePin()
                else if (event.keyCode == KeyEvent.KEYCODE_M && sharedPreferences.getBoolean(
                        "enable_open_music",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_music", "")!!)
                else if (event.keyCode == KeyEvent.KEYCODE_B && sharedPreferences.getBoolean(
                        "enable_open_browser",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_browser", "")!!)
                else if (event.keyCode == KeyEvent.KEYCODE_A && sharedPreferences.getBoolean(
                        "enable_open_assist",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_assistant", "")!!)
                else if (event.keyCode == KeyEvent.KEYCODE_R && sharedPreferences.getBoolean(
                        "enable_open_rec",
                        true
                    )
                )
                    launchApp(null, sharedPreferences.getString("app_rec", "")!!)
                else if (event.keyCode == KeyEvent.KEYCODE_D)
                    startActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                else if (event.keyCode == KeyEvent.KEYCODE_O) {
                    toggleSoftKeyboard()
                } else if (event.keyCode == KeyEvent.KEYCODE_F12)
                    DeviceUtils.softReboot()
                //Window management
                else if (event.keyCode == KeyEvent.KEYCODE_F3) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        AppUtils.resizeTask(
                            context, "portrait", task.id, dockHeight
                        )
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "maximized",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "maximized", task.id, dockHeight
                            )
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "tiled-left",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "tiled-left", task.id, dockHeight
                            )
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "tiled-right",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "tiled-right", task.id, dockHeight
                            )
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (tasks.isNotEmpty()) {
                        val task = tasks[0]
                        if (event.isShiftPressed)
                            launchApp(
                                "standard",
                                task.packageName,
                                newInstance = true,
                                rememberMode = false
                            )
                        else
                            AppUtils.resizeTask(
                                context, "standard", task.id, dockHeight
                            )
                    }
                } else if (event.isShiftPressed) {
                    val index = when (event.keyCode) {
                        KeyEvent.KEYCODE_1 -> 0
                        KeyEvent.KEYCODE_2 -> 1
                        KeyEvent.KEYCODE_3 -> 2
                        KeyEvent.KEYCODE_4 -> 3
                        KeyEvent.KEYCODE_N -> 4
                        else -> -1
                    }
                    if (index == 4 && sharedPreferences.getBoolean("enable_new_instance", true)) {
                        if (tasks.isNotEmpty()) {
                            val task = tasks[0]
                            launchApp(null, task.packageName, newInstance = true)
                        }
                    } else if (index != -1 && sharedPreferences.getBoolean("enable_tiling", true)) {
                        val displays = DeviceUtils.getDisplays(this)
                        if (tasks.isNotEmpty() && displays.size > index) {
                            val task = tasks[0]
                            launchApp(null, task.packageName, displayId = displays[index].displayId)
                        }
                    }
                }
            } else {
                if (event.keyCode == KeyEvent.KEYCODE_CTRL_RIGHT && sharedPreferences.getBoolean(
                        "enable_ctrl_back",
                        true
                    )
                ) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_MENU && sharedPreferences.getBoolean(
                        "enable_menu_recents",
                        false
                    )
                ) {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_F10 && sharedPreferences.getBoolean(
                        "enable_f10",
                        true
                    )
                ) {
                    performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    return true
                } else if ((event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_META_LEFT ||
                            event.keyCode == KeyEvent.KEYCODE_META_RIGHT) && sharedPreferences.getBoolean(
                        "enable_open_menu",
                        true
                    )
                ) {
                    toggleAppMenu()
                    return true
                }
            }
        }

        return super.onKeyEvent(event)
    }

    private fun toggleSoftKeyboard() {
        //TODO
        val kc = softKeyboardController
        val mode = kc.showMode
        if (mode == SHOW_MODE_AUTO || mode == SHOW_MODE_HIDDEN)
            kc.showMode = SHOW_MODE_IGNORE_HARD_KEYBOARD
        else
            kc.showMode = SHOW_MODE_HIDDEN
    }

    /**
     * Remembers which app each window belongs to, from the window events (package name only, never
     * window content). The window may not be in [getWindows] yet when its event arrives, so the
     * match is made later, when checking the active window.
     */
    private fun rememberForegroundApp(event: AccessibilityEvent) {
        val app = event.packageName?.toString() ?: return
        if (event.windowId != -1) windowPackages[event.windowId] = app
    }

    /** True when the app in front is not a launcher and its window covers the whole screen. */
    private fun isFullscreenAppInFront(): Boolean {
        val current = windows
        if (windowPackages.size > 64) {
            val alive = current.map { it.id }.toSet()
            windowPackages.keys.retainAll(alive)
        }
        val window = current.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused)
        } ?: return false
        // Unknown window (no event seen yet): keep the bar
        val app = windowPackages[window.id] ?: return false
        if (app == packageName || app in homePackages) return false
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        // The real screen right now, from the service's own window manager
        val screen = windowManager.maximumWindowMetrics.bounds
        // Full screen = the window goes from the top edge to the bottom edge, under the bar. Width is
        // not checked: portrait-only apps run full screen with black bands on the sides. HyperOS
        // floating windows always start below the status bar.
        val edge = screen.height() * 0.02f
        return bounds.top <= screen.top + edge && bounds.bottom >= screen.bottom - edge
    }

    private fun autoHideEnabled() = sharedPreferences.getBoolean(KEY_AUTOHIDE_FULLSCREEN, true)

    private fun checkFullscreenApp() {
        if (dock == null) return
        if (autoHideEnabled() && isFullscreenAppInFront()) {
            if (isPinned && !autoHideSuppressed && !autoHideHandler.hasCallbacks(autoHideRunnable)) {
                Log.d("EG", "taskbar: full-screen app in front, hiding in $TASKBAR_AUTOHIDE_DELAY_MS ms")
                autoHideHandler.postDelayed(autoHideRunnable, TASKBAR_AUTOHIDE_DELAY_MS)
            }
        } else {
            autoHideHandler.removeCallbacks(autoHideRunnable)
            autoHideSuppressed = false
            if (autoHidden) pinDock()
        }
    }

    private fun autoHideTaskbar() {
        if (!isPinned || !autoHideEnabled() || !isFullscreenAppInFront()) return
        if (appMenuVisible || quickSettingsPanelVisible || powerMenuVisible || notificationCenterVisible ||
            calendarPanelVisible
        ) {
            // Try again once the open panel is closed
            autoHideHandler.postDelayed(autoHideRunnable, TASKBAR_AUTOHIDE_DELAY_MS)
            return
        }
        Log.d("EG", "taskbar: hidden over a full-screen app")
        autoHidden = true
        isPinned = false
        hideDock(0, auto = true)
    }

    private fun togglePin() {
        if (autoHidden) {
            // Shown with the mouse over a full-screen app: keep it until that app goes away
            autoHideSuppressed = true
            pinDock()
            return
        }
        if (isPinned) unpinDock() else pinDock()
    }

    private fun showDock() {
        //Hack to hide the dock on the lockscreen at boot
        dock!!.isVisible = !keyguardManager.isKeyguardLocked
        dockHandle!!.visibility = View.GONE

        if (dockLayoutParams.height != dockHeight) {
            dockLayoutParams.height = dockHeight
            windowManager.updateViewLayout(dock, dockLayoutParams)
        }

        dockHandler.removeCallbacksAndMessages(null)
        updateRunningTasks()
        val anim = AnimationUtils.loadAnimation(context, R.anim.slide_up)
        dockLayout.visibility = View.VISIBLE
        dockLayout.startAnimation(anim)
    }

    fun pinDock() {
        isPinned = true
        autoHidden = false
        pinBtn.setImageResource(R.drawable.ic_pin)
        if (dockLayout.isGone)
            showDock()
    }

    private fun unpinDock() {
        pinBtn.setImageResource(R.drawable.ic_unpin)
        isPinned = false
        if (dockLayout.isVisible)
            hideDock(500)
    }

    private fun hideDock(delay: Int, auto: Boolean = false) {
        dockHandler.removeCallbacksAndMessages(null)
        dockHandler.postDelayed({
            if (!isPinned) {
                val animation = AnimationUtils.loadAnimation(context, R.anim.slide_down)
                animation.setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(p1: Animation) {}
                    override fun onAnimationEnd(p1: Animation) {
                        dockLayout.visibility = View.GONE
                        val activationMethod =
                            sharedPreferences.getString("activation_method", "swipe")!!
                        if (auto || activationMethod == "swipe") {
                            val height =
                                sharedPreferences.getString("dock_activation_area", "10")!!.toInt()
                            dockLayoutParams.height = Utils.dpToPx(context, height)
                            windowManager.updateViewLayout(dock, dockLayoutParams)
                        } else {
                            dock!!.visibility = View.GONE
                            dockHandle!!.visibility = View.VISIBLE
                        }
                        if (!auto && sharedPreferences.getBoolean("first_hide", true))
                            showFirstDockHideDialog(activationMethod)
                    }

                    override fun onAnimationRepeat(p1: Animation) {}
                })
                dockLayout.startAnimation(animation)
            }
        }, delay.toLong())
    }

    private fun getDefaultLaunchMode(app: String?): String {
        if (app == null)
            return "standard"
        val mode: String? = db.getLaunchMode(app)
        return if (sharedPreferences.getBoolean("remember_launch_mode", true) && mode != null)
            mode
        else if (AppUtils.isGame(
                packageManager,
                app
            ) && sharedPreferences.getBoolean("launch_games_fullscreen", true)
        )
            "fullscreen"
        else
            sharedPreferences.getString("launch_mode", "standard")!!
    }

    private fun launchApp(
        mode: String?,
        packageName: String?,
        intent: Intent? = null,
        app: App? = null,
        displayId: Int = Display.DEFAULT_DISPLAY,
        newInstance: Boolean = false,
        rememberMode: Boolean = true
    ) {
        var launchMode = mode
        if (launchMode == null)
            launchMode = getDefaultLaunchMode(packageName)
        else
            if (rememberMode && sharedPreferences.getBoolean(
                    "remember_launch_mode",
                    true
                ) && packageName != null
            )
                db.saveLaunchMode(packageName, launchMode)

        val options = AppUtils.makeActivityOptions(context, launchMode, dockHeight, displayId)

        //Used only for work apps
        if (app != null && app.userHandle != Process.myUserHandle())
            launcherApps.startMainActivity(
                app.componentName,
                app.userHandle,
                null,
                options.toBundle()
            )
        else {
            val launchIntent: Intent? = if (intent == null && packageName != null)
                packageManager.getLaunchIntentForPackage(packageName)
            else
                intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (launchIntent == null)
                return

            if (newInstance)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(launchIntent, options.toBundle())
        }

        if (appMenuVisible)
            hideAppMenu()

        if (launchMode == "fullscreen" && sharedPreferences.getBoolean("auto_unpin", true) &&
            !autoHideEnabled()
        ) {
            if (isPinned)
                unpinDock()
        } else {
            if (!isPinned && sharedPreferences.getBoolean("auto_pin", true))
                pinDock()
        }
        //Hack to ensure the launched app is already on the top of the stack
        dockHandler.postDelayed({ updateRunningTasks() }, 1200)

        hideFlyouts()
    }

    private fun setOrientation() {
        dockLayoutParams.screenOrientation =
            if (sharedPreferences.getBoolean("lock_landscape", false))
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        windowManager.updateViewLayout(dock, dockLayoutParams)
    }

    private fun toggleAppMenu() {
        if (appMenuVisible)
            hideAppMenu()
        else
            showAppMenu()
    }

    fun showAppMenu() {
        hideFlyouts()
        val displayId =
            if (preferSecondaryDisplay) DeviceUtils.getSecondaryDisplay(context).displayId else Display.DEFAULT_DISPLAY
        val bounds = DeviceUtils.getDisplayBounds(context, displayId)
        val gap = Utils.dpToPx(context, START_MENU_GAP_DP)
        val statusBarHeight = DeviceUtils.getStatusBarHeight(context)
        // 560 x 434 dp like the mockup; smaller when the screen can't fit it, never over the taskbar
        val width = Utils.dpToPx(context, START_MENU_WIDTH_DP).coerceAtMost(bounds.width() - gap * 2)
        val height = Utils.dpToPx(context, START_MENU_HEIGHT_DP)
            .coerceAtMost(bounds.height() - dockHeight - gap - statusBarHeight)
        val layoutParams = Utils.makeWindowParams(width, height, context, preferSecondaryDisplay)
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        // The on-screen keyboard only opens when the search box is tapped
        layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        layoutParams.y = dockHeight + gap
        ColorUtils.applyMainColor(context, sharedPreferences, appMenu!!, appMenuSurface())

        // Without room for a row of pinned apps, "Recomendado" makes way
        recommendedSection.isVisible = height - Utils.dpToPx(context, START_MENU_FIXED_DP) >=
                Utils.dpToPx(context, START_MENU_MIN_GRID_DP)
        showAllRecent = false
        showStartHome()
        updateAppMenu()
        loadFavoriteApps()
        loadRecentApps()
        updateStartUser()

        // With a physical keyboard, typing goes straight to the search box
        val hardwareKeyboard =
            context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
        searchEt.showSoftInputOnFocus = !hardwareKeyboard
        if (hardwareKeyboard) searchEt.requestFocus() else searchEt.clearFocus()

        // Still fading out from the last close: take that window away first
        PanelAnimator.cancel(appMenu!!)
        if (appMenu!!.isAttachedToWindow) windowManager.removeViewImmediate(appMenu)
        windowManager.addView(appMenu, layoutParams)
        PanelAnimator.show(appMenu!!)
        appMenuVisible = true
        appsBtn.isActivated = true
    }

    fun hideAppMenu() {
        searchEt.setText("") // back to the home view next time
        appMenuVisible = false
        appsBtn.isActivated = false
        val menu = appMenu ?: return
        if (!menu.isAttachedToWindow) return
        PanelAnimator.hide(menu) {
            if (!appMenuVisible && menu.isAttachedToWindow) windowManager.removeView(menu)
        }
    }

    private fun showStartHome() {
        startSearching = false
        startHome.isVisible = true
        startAll.isVisible = false
    }

    /** "Todas las apps" (A-Z), or the search results while [searching]. */
    private fun showStartAll(searching: Boolean) {
        startHome.isVisible = false
        startAll.isVisible = true
        allAppsTitleTv.setText(if (searching) R.string.start_results else R.string.start_all_apps_title)
        val empty = (appsGv.adapter as? AppListAdapter)?.isEmpty ?: false
        noResultsTv.isVisible = searching && empty
        appsGv.isVisible = !(searching && empty)
        appsGv.scrollToPosition(0)
    }

    private fun onStartSearch(text: String) {
        (appsGv.adapter as? AppListAdapter)?.filter(text)
        if (text.isNotBlank()) {
            startSearching = true
            showStartAll(searching = true)
        } else if (startSearching)
            showStartHome()
    }

    private fun launchFirstStartResult() {
        (appsGv.adapter as? AppListAdapter)?.firstApp?.let { onAppClicked(it, appsGv) }
    }

    /** "Recomendado": the most recently used apps (2, or up to 6 with "Más"). */
    private fun loadRecentApps() {
        val exclude = homePackages + packageName
        val apps = installedApps
        CoroutineScope(Dispatchers.Default).launch {
            val recent = StartMenuUtils.recentApps(context, apps, exclude, START_RECENT_MAX)
            withContext(Dispatchers.Main) {
                if (appMenu == null) return@withContext
                val shown = if (showAllRecent) recent else recent.take(START_RECENT_DEFAULT)
                recentLv.adapter = RecentAppsAdapter(context, shown, this@DockService, iconPackUtils)
                recentLv.isVisible = shown.isNotEmpty()
                recentEmptyTv.isVisible = shown.isEmpty()
                moreRecentBtn.isVisible = recent.size > START_RECENT_DEFAULT
                moreRecentBtn.setText(if (showAllRecent) R.string.start_less else R.string.start_more)
                fitPinnedRows()
            }
        }
    }

    /** Footer: the user's name (Menú de aplicaciones > Nombre) and their picture or initial. */
    private fun updateStartUser() {
        var name = sharedPreferences.getString("user_name", "")!!.trim()
        var picture: Bitmap? = null
        if (AppUtils.isSystemApp(context, packageName)) {
            DeviceUtils.getUserName(context)?.let { name = it }
            picture = DeviceUtils.getUserIcon(context)
        } else {
            val iconUri = sharedPreferences.getString("user_icon_uri", "default")!!
            if (iconUri != "default")
                picture = Utils.getCircularBitmap(Utils.getBitmapFromUri(context, iconUri.toUri()))
        }
        if (name.isEmpty()) name = getString(R.string.user)
        startUserNameTv.text = name
        startAvatarIv.isVisible = picture != null
        startAvatarInitialTv.isVisible = picture == null
        if (picture != null) startAvatarIv.setImageBitmap(picture)
        else startAvatarInitialTv.text = name.first().uppercase()
    }

    private fun showStartPowerMenu(anchor: View) {
        if (sharedPreferences.getBoolean("enable_power_menu", false)) {
            hideAppMenu()
            if (powerMenuVisible) hidePowerMenu() else showPowerMenu()
            return
        }
        val actions = arrayListOf(
            Action(R.drawable.ic_lock, getString(R.string.start_lock)),
            Action(R.drawable.ic_close, getString(R.string.start_exit_desktop)),
            Action(R.drawable.ic_power, getString(R.string.start_power_options))
        )
        showPopupAbove(anchor, actions) { position ->
            hideAppMenu()
            when (position) {
                0 -> lockScreen()
                1 -> exitDesktop()
                else -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            }
        }
    }

    /**
     * Leaves desktop mode so the phone works as usual: the service turns itself off, which removes the
     * taskbar, the landscape lock and restores the user's screen scale (onDestroy). Opening the
     * Escritorio Glass app offers to come back.
     */
    private fun exitDesktop() {
        Toast.makeText(this, R.string.desktop_off_toast, Toast.LENGTH_LONG).show()
        disableSelf()
    }

    /** Small glass list of actions right above [anchor], aligned to its right edge. */
    private fun showPopupAbove(anchor: View, actions: ArrayList<Action>, onAction: (Int) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, preferSecondaryDisplay)
        ColorUtils.applyMainColor(context, sharedPreferences, view, GlassSurface.POPUP)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val bounds = DeviceUtils.getDisplayBounds(context, Display.DEFAULT_DISPLAY)
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = bounds.width() - (location[0] + anchor.width)
        layoutParams.y = bounds.height() - location[1] + Utils.dpToPx(context, 4)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && view.isAttachedToWindow)
                windowManager.removeView(view)
            false
        }
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        actionsLv.adapter = AppActionsAdapter(context, actions)
        actionsLv.setOnItemClickListener { _, _, position, _ ->
            if (view.isAttachedToWindow) windowManager.removeView(view)
            onAction(position)
        }
        windowManager.addView(view, layoutParams)
        PanelAnimator.show(view)
    }

    private suspend fun fetchInstalledApps(): ArrayList<App> = withContext(Dispatchers.Default) {
        return@withContext AppUtils.getInstalledApps(context)
    }

    private fun updateAppMenu(recreateAdapter: Boolean = false) {
        CoroutineScope(Dispatchers.Default).launch {
            val hiddenApps = sharedPreferences.getStringSet(
                "hidden_apps_grid",
                setOf()
            )!!
            val collator = Collator.getInstance()
            val apps = fetchInstalledApps().filterNot { hiddenApps.contains(it.packageName) }
                .sortedWith { a, b -> collator.compare(a.name, b.name) }

            withContext(Dispatchers.Main) {
                installedApps = apps
                val existingAdapter = appsGv.adapter
                if (existingAdapter is AppListAdapter && !recreateAdapter)
                    existingAdapter.updateApps(apps)
                else
                    appsGv.adapter = AppListAdapter(context, apps, this@DockService, iconPackUtils)
                if (appMenuVisible) loadRecentApps()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showDockAppContextMenu(app: DockApp, anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, preferSecondaryDisplay)
        view.setBackgroundResource(R.drawable.round_rect)
        ColorUtils.applyMainColor(context, sharedPreferences, view, GlassSurface.POPUP)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.START
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.y = Utils.dpToPx(context, 2) + dockHeight
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && view.isAttachedToWindow)
                windowManager.removeView(view)

            false
        }
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        actionsLv.adapter = AppActionsAdapter(context, getDockAppActions(app))
        actionsLv.setOnItemClickListener { adapterView, _, position, _ ->
            if (adapterView.getItemAtPosition(position) is Action) {
                val action = adapterView.getItemAtPosition(position) as Action
                if (action.text == getString(R.string.pin) || action.text == getString(R.string.unpin)) {
                    if (AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST))
                        AppUtils.unpinApp(
                            context,
                            app.packageName,
                            AppUtils.DOCK_PINNED_LIST
                        ) else
                        AppUtils.pinApp(context, app, AppUtils.DOCK_PINNED_LIST)
                    loadPinnedApps()
                    if (isPinned)
                        updateRunningTasks()
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.start_pin) ||
                    action.text == getString(R.string.start_unpin)
                ) {
                    toggleStartPin(app)
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.left)) {
                    AppUtils.moveApp(this, app, AppUtils.DOCK_PINNED_LIST, 0)
                    loadPinnedApps()
                    updateRunningTasks()
                } else if (action.text == getString(R.string.right)) {
                    AppUtils.moveApp(this, app, AppUtils.DOCK_PINNED_LIST, 1)
                    loadPinnedApps()
                    updateRunningTasks()
                } else if (action.text == getString(R.string.move)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_arrow_back, getString(R.string.left)))
                    actions.add(Action(R.drawable.ic_arrow_right, getString(R.string.right)))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.resize)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_standard, getString(R.string.standard)))
                    actions.add(Action(R.drawable.ic_maximized, getString(R.string.maximized)))
                    actions.add(Action(R.drawable.ic_portrait, getString(R.string.portrait)))
                    actions.add(Action(R.drawable.ic_fullscreen, getString(R.string.fullscreen)))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.snap)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_snap_top, getString(R.string.top)))
                    actions.add(Action(R.drawable.ic_snap_bottom, getString(R.string.bottom)))
                    actions.add(Action(R.drawable.ic_snap_left, getString(R.string.left)))
                    actions.add(Action(R.drawable.ic_snap_right, getString(R.string.right)))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.standard)) {
                    activityManagerWrapper?.resizeTask(
                        app.tasks[0].id,
                        AppUtils.makeLaunchBounds(this, "standard", dockHeight)
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.maximized)) {
                    activityManagerWrapper?.resizeTask(
                        app.tasks[0].id,
                        AppUtils.makeLaunchBounds(this, "maximized", dockHeight)
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.portrait)) {
                    activityManagerWrapper?.resizeTask(
                        app.tasks[0].id,
                        AppUtils.makeLaunchBounds(this, "portrait", dockHeight)
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.fullscreen)) {
                    activityManagerWrapper?.resizeTask(
                        app.tasks[0].id,
                        AppUtils.makeLaunchBounds(this, "fullscreen", dockHeight)
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.top)) {
                    activityManagerWrapper?.resizeTask(
                        app.tasks[0].id,
                        AppUtils.makeLaunchBounds(this, "tiled-top", dockHeight)
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.bottom)) {
                    activityManagerWrapper?.resizeTask(
                        app.tasks[0].id,
                        AppUtils.makeLaunchBounds(this, "tiled-bottom", dockHeight)
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.close)) {
                    activityManagerWrapper?.removeTask(app.tasks[0].id)
                    windowManager.removeView(view)
                } else if (action.text.isBlank()) {
                    actionsLv.adapter = AppActionsAdapter(context, getDockAppActions(app))
                }
            }
        }
        windowManager.addView(view, layoutParams)
        PanelAnimator.show(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAppContextMenu(app: App, anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, preferSecondaryDisplay)
        ColorUtils.applyMainColor(context, sharedPreferences, view, GlassSurface.POPUP)
        layoutParams.gravity = Gravity.START or Gravity.TOP
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        layoutParams.y = location[1] + Utils.dpToPx(context, anchor.measuredHeight / 2)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && view.isAttachedToWindow)
                windowManager.removeView(view)

            false
        }
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        actionsLv.adapter = AppActionsAdapter(context, getAppActions(app))
        actionsLv.setOnItemClickListener { adapterView, _, position, _ ->
            if (adapterView.getItemAtPosition(position) is Action) {
                val action = adapterView.getItemAtPosition(position) as Action
                if (action.text == getString(R.string.manage)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_info, getString(R.string.app_info)))
                    if (sharedPreferences.getBoolean("enable_app_hiding_grid", false))
                        actions.add(
                            Action(
                                R.drawable.ic_hide,
                                getString(R.string.hide)
                            )
                        )
                    if (!AppUtils.isSystemApp(
                            context,
                            app.packageName
                        ) || sharedPreferences.getBoolean("allow_sysapp_uninstall", false)
                    ) actions.add(Action(R.drawable.ic_uninstall, getString(R.string.uninstall)))
                    if (sharedPreferences.getBoolean("allow_app_freeze", false))
                        actions.add(
                            Action(
                                R.drawable.ic_freeze,
                                getString(R.string.freeze)
                            )
                        )
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.shortcuts)) {
                    actionsLv.adapter = AppShortcutAdapter(
                        context,
                        DeepShortcutManager.getShortcuts(app.packageName, context)!!
                    )
                } else if (action.text == "") {
                    actionsLv.adapter = AppActionsAdapter(context, getAppActions(app))
                } else if (action.text == getString(R.string.open_as)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_standard, getString(R.string.standard)))
                    actions.add(Action(R.drawable.ic_maximized, getString(R.string.maximized)))
                    actions.add(Action(R.drawable.ic_portrait, getString(R.string.portrait)))
                    actions.add(Action(R.drawable.ic_fullscreen, getString(R.string.fullscreen)))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.add_to)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.addAll(getPinActions(app))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.launch_in)) {
                    actionsLv.adapter = DisplaysAdapter(context, DeviceUtils.getDisplays(this))
                } else if (action.text == getString(R.string.app_info)) {
                    launchApp(
                        null, null, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData("package:${app.packageName}".toUri())
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.hide)) {
                    val savedApps = sharedPreferences.getStringSet(
                        "hidden_apps_grid",
                        setOf()
                    )!!
                    val hiddenApps = mutableSetOf<String>()
                    hiddenApps.addAll(savedApps)
                    hiddenApps.add(app.packageName)

                    sharedPreferences.edit {
                        putStringSet("hidden_apps_grid", hiddenApps)
                    }

                    if (AppUtils.isPinned(this, app, AppUtils.PINNED_LIST))
                        AppUtils.unpinApp(this, app.packageName, AppUtils.PINNED_LIST)
                    if (AppUtils.isPinned(this, app, AppUtils.DOCK_PINNED_LIST))
                        AppUtils.unpinApp(this, app.packageName, AppUtils.DOCK_PINNED_LIST)
                    if (AppUtils.isPinned(this, app, AppUtils.DESKTOP_LIST))
                        AppUtils.unpinApp(this, app.packageName, AppUtils.DESKTOP_LIST)
                    updateAppMenu()
                    loadFavoriteApps()
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.uninstall)) {
                    AppUtils.uninstallApp(this, app.packageName)
                    if (appMenuVisible)
                        hideAppMenu()
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.freeze)) {
                    val status = DeviceUtils.runAsRoot("pm disable ${app.packageName}")
                    if (status != "error") Toast.makeText(
                        context,
                        R.string.app_frozen,
                        Toast.LENGTH_SHORT
                    ).show() else Toast.makeText(
                        context,
                        R.string.something_wrong,
                        Toast.LENGTH_SHORT
                    ).show()
                    windowManager.removeView(view)
                    if (appMenuVisible) hideAppMenu()
                } else if (action.text == getString(R.string.start_pin) ||
                    action.text == getString(R.string.start_unpin)
                ) {
                    toggleStartPin(app)
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.desktop)) {
                    AppUtils.pinApp(context, app, AppUtils.DESKTOP_LIST)
                    sendBroadcast(
                        Intent(DOCK_SERVICE_ACTION)
                            .setPackage(packageName)
                            .putExtra("action", DESKTOP_APP_PINNED)
                    )
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.dock)) {
                    AppUtils.pinApp(context, app, AppUtils.DOCK_PINNED_LIST)
                    loadPinnedApps()
                    updateRunningTasks()
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.standard)) {
                    windowManager.removeView(view)
                    launchApp("standard", app.packageName, null, app, newInstance = true)
                } else if (action.text == getString(R.string.maximized)) {
                    windowManager.removeView(view)
                    launchApp("maximized", app.packageName, null, app, newInstance = true)
                } else if (action.text == getString(R.string.portrait)) {
                    windowManager.removeView(view)
                    launchApp("portrait", app.packageName, null, app, newInstance = true)
                } else if (action.text == getString(R.string.fullscreen)) {
                    windowManager.removeView(view)
                    launchApp("fullscreen", app.packageName, null, app, newInstance = true)
                }
            } else if (adapterView.getItemAtPosition(position) is ShortcutInfo) {
                val shortcut = adapterView.getItemAtPosition(position) as ShortcutInfo
                windowManager.removeView(view)
                DeepShortcutManager.startShortcut(shortcut, context)
            } else if (adapterView.getItemAtPosition(position) is Display) {
                val display = adapterView.getItemAtPosition(position) as Display
                windowManager.removeView(view)
                launchApp(
                    null,
                    app.packageName,
                    null,
                    app,
                    display.displayId,
                    sharedPreferences.getBoolean("launch_new_instance_secondary", true)
                )
            }
        }
        windowManager.addView(view, layoutParams)
        PanelAnimator.show(view)
    }

    override fun onSharedPreferenceChanged(p1: SharedPreferences, preference: String?) {
        if (preference == null)
            return
        if (preference == KEY_AUTOHIDE_FULLSCREEN) {
            checkFullscreenApp()
            return
        }
        if (preference == DesktopScale.KEY_APP_SCALE) {
            // The new density reaches onConfigurationChanged, which rebuilds the bar at its own scale
            DesktopScale.apply(this, sharedPreferences)
            return
        }
        if (preference.startsWith("theme"))
            applyTheme()
        else if (preference == "menu_icon_uri")
            updateMenuIcon()
        else if (preference.startsWith("icon_")) {
            val iconPack = sharedPreferences.getString("icon_pack", "")!!
            iconPackUtils = if (iconPack.isNotEmpty()) {
                IconPackUtils(this)
            } else
                null

            updateRunningTasks(true)
            updateAppMenu(true)
            loadFavoriteApps()
        } else if (preference == "tint_indicators" || preference == "glass_accent") {
            updateRunningTasks(true)
            updateMenuIcon()
            updateNotificationCount()
        } else if (preference == "lock_landscape")
            setOrientation()
        else if (preference == "center_running_apps") {
            placeRunningApps()
            updateRunningTasks()
        } else if (preference == "dock_activation_area")
            updateDockTrigger()
        else if (preference.startsWith("enable_corner_"))
            updateCorners()
        else if (preference.startsWith("enable_nav_")) {
            updateNavigationBar()
        } else if (preference.startsWith("enable_qs_")) {
            updateQuickSettings()
        } else if (preference == "round_dock")
            updateDockShape()
        else if (preference.startsWith("max_running_apps")) {
            maxApps = sharedPreferences.getString("max_running_apps", "10")!!.toInt()
            maxAppsLandscape =
                sharedPreferences.getString("max_running_apps_landscape", "10")!!.toInt()
            updateRunningTasks()
        } else if (preference == "activation_method") {
            updateActivationMethod()
        } else if (preference == "handle_opacity")
            dockHandle!!.alpha = sharedPreferences.getString("handle_opacity", "0.5")!!.toFloat()
        else if (preference == "dock_height")
            updateDockHeight()
        else if (preference == "handle_position")
            updateHandlePosition()
        else if (preference.startsWith("show_battery_level"))
            updateBatteryBtn()
        else if (preference == "dock_background_alpha")
            applyDockAlpha()
        else if (preference == "override_dock_background_alpha")
            updateDockBackgroundColor()
        else if (preference == "prefer_last_display") {
            preferSecondaryDisplay = sharedPreferences.getBoolean("prefer_last_display", false)
            if (DeviceUtils.getDisplays(this).size > 1)
                restartUI()
        }
    }

    private fun updateDockTrigger() {
        if (!isPinned) {
            val height = sharedPreferences.getString("dock_activation_area", "10")!!.toInt()
            dockLayoutParams.height = Utils.dpToPx(context, height)
            windowManager.updateViewLayout(dock, dockLayoutParams)
        }
    }

    private fun updateActivationMethod() {
        if (!isPinned) {
            val method = sharedPreferences.getString("activation_method", "swipe")
            if (method == "swipe") {
                dockHandle!!.visibility = View.GONE
                updateDockTrigger()
                dock!!.visibility = View.VISIBLE
            } else {
                dock!!.visibility = View.GONE
                dockHandle!!.visibility = View.VISIBLE
            }
        }
    }

    /** Window height: the floating bar plus its bottom margin (docs/DISENO.md). */
    private fun taskbarWindowHeight(): Int {
        val barHeight = sharedPreferences.getString("dock_height", "50")!!.toIntOrNull() ?: 50
        return Utils.dpToPx(context, barHeight + TASKBAR_MARGIN_BOTTOM_DP)
    }

    private fun updateDockHeight() {
        dockHeight = taskbarWindowHeight()
        if (isPinned) {
            dockLayoutParams.height = dockHeight
            windowManager.updateViewLayout(dock, dockLayoutParams)
        }
    }

    /** Centred (Windows 11 default) or left-aligned taskbar icons ("center_running_apps"). */
    private fun placeRunningApps() {
        taskbarGroupStart = -1
        fitRunningApps(runningAppsCount)
    }

    /** Left offset of the centre group inside the bar (-1 = not placed yet). */
    private var taskbarGroupStart = -1

    /**
     * Windows 11 behaviour: Start + apps stay centred on the screen; as more apps open the group
     * slides sideways only as much as needed so it never covers the date block or the tray, and
     * only then the list scrolls. The list always shows whole icons: half an icon looks cut off.
     */
    private fun fitRunningApps(count: Int) {
        if (!::tasksGv.isInitialized || !::centerGroup.isInitialized) return
        val itemWidth = Utils.dpToPx(context, TASKBAR_ITEM_WIDTH_DP)
        var width = itemWidth * count
        val barWidth = dockLayout.width - dockLayout.paddingStart - dockLayout.paddingEnd
        // Before the first layout the XML keeps the group centred; the layout listener calls us again
        if (barWidth > 0) {
            val left = if (dateBlock.isVisible) dateBlock.width else 0
            val right = systemTray.width
            val gap = Utils.dpToPx(context, 8)
            val nav = navPanel.width
            val room = barWidth - left - right - 2 * gap - nav
            width = width.coerceAtMost(max(1, room / itemWidth) * itemWidth)
            val group = nav + width
            val first = left + gap
            val last = max(first, barWidth - right - gap - group)
            val start = if (sharedPreferences.getBoolean("center_running_apps", true))
                ((barWidth - group) / 2).coerceIn(first, last) else first
            if (taskbarGroupStart != start) {
                taskbarGroupStart = start
                centerGroup.layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                    marginStart = start
                }
            }
        }
        if (tasksGv.layoutParams.width != width) {
            tasksGv.layoutParams.width = width
            tasksGv.requestLayout()
        }
    }

    private fun loadPinnedApps() {
        pinnedApps = AppUtils.getPinnedApps(context, AppUtils.DOCK_PINNED_LIST)
    }

    private fun updateRunningTasks(recreateAdapter: Boolean = false) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 500 && !recreateAdapter)
            return
        lastUpdate = now

        val apps = ArrayList<DockApp>()
        if (::pinnedApps.isInitialized)
            pinnedApps.forEach { pinnedApp ->
                apps.add(DockApp(pinnedApp.name, pinnedApp.packageName, pinnedApp.icon))
            }

        //TODO: We can eliminate another for
        //TODO: Don't do anything if tasks has not changed
        val nApps =
            if (orientation == Configuration.ORIENTATION_PORTRAIT) maxApps else maxAppsLandscape
        if (systemApp || DeviceUtils.hasShizukuPermission()) {
            if (systemApp)
                tasks = AppUtils.getRunningTasks(activityManager, null, packageManager, nApps)
            else if (DeviceUtils.hasShizukuPermission())
                tasks = AppUtils.getRunningTasks(
                    activityManager,
                    activityManagerWrapper,
                    packageManager,
                    nApps
                )
            for (j in 1..tasks.size) {
                val task = tasks[tasks.size - j]
                val index = AppUtils.containsTask(apps, task)
                if (index != -1)
                    apps[index].addTask(task)
                else
                    apps.add(DockApp(task))
            }
        } else {
            tasks = AppUtils.getRecentTasks(context, nApps)
            tasks.reversed().forEach { task ->
                val index = AppUtils.containsTask(apps, task)
                if (index == -1)
                    apps.add(DockApp(task))
            }
        }

        runningAppsCount = apps.size
        fitRunningApps(apps.size)
        val adapter = tasksGv.adapter
        if (adapter is DockAppAdapter && !recreateAdapter)
            adapter.updateApps(apps)
        else
            tasksGv.adapter = DockAppAdapter(context, apps, this, iconPackUtils)
        // When there are more apps than room the list scrolls: keep the app in front in view,
        // aligned to whole icons so the first one is never cut in half
        val active = apps.indexOfFirst { it.packageName == AppUtils.currentApp }
        if (active != -1) {
            val visible = max(1, tasksGv.layoutParams.width / Utils.dpToPx(context, TASKBAR_ITEM_WIDTH_DP))
            (tasksGv.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(max(0, active - visible + 1), 0)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orientation = newConfig.orientation
        orientationValue =
            if (orientation == Configuration.ORIENTATION_PORTRAIT) "" else "_landscape"
        // A new screen scale (density) changes every dp size: rebuild the bar and its windows
        if (densityDpi != 0 && newConfig.densityDpi != densityDpi) {
            densityDpi = newConfig.densityDpi
            hideFlyouts()
            if (powerMenuVisible) hidePowerMenu()
            restartUI()
            return
        }
        densityDpi = newConfig.densityDpi
        updateRunningTasks()
        updateNavigationBar()
        updateQuickSettings()
        updateBatteryBtn()
        updateDateBlock()
        if (appMenuVisible)
            hideAppMenu()
    }

    private fun updateDockShape() {
        dockLayout.setBackgroundResource(
            if (sharedPreferences.getBoolean(
                    "round_dock",
                    false
                )
            ) R.drawable.round_rect else R.drawable.rect
        )
        updateDockBackgroundColor()
    }

    private fun updateNavigationBar() {
        appsBtn.visibility =
            if (sharedPreferences.getBoolean(
                    "enable_nav_apps$orientationValue",
                    true
                )
            ) View.VISIBLE else View.GONE
        // Back and Home are optional on a phone with gesture navigation (hidden by default)
        backBtn.visibility =
            if (sharedPreferences.getBoolean(
                    "enable_nav_back$orientationValue",
                    false
                )
            ) View.VISIBLE else View.GONE
        homeBtn.visibility =
            if (sharedPreferences.getBoolean(
                    "enable_nav_home$orientationValue",
                    false
                )
            ) View.VISIBLE else View.GONE
        recentBtn.visibility = if (sharedPreferences.getBoolean(
                "enable_nav_recents$orientationValue",
                true
            )
        ) View.VISIBLE else View.GONE
        assistBtn.visibility = if (sharedPreferences.getBoolean(
                "enable_nav_assist$orientationValue",
                false
            )
        ) View.VISIBLE else View.GONE
        applyTaskbarOrientation()
    }

    /** Compact bar in portrait: no date block, Search or Task view, smaller margins. */
    private fun applyTaskbarOrientation() {
        val portrait = orientation == Configuration.ORIENTATION_PORTRAIT
        dateBlock.isVisible = !portrait
        searchBtn.isVisible = !portrait
        if (portrait) recentBtn.isVisible = false
        dateLineTv.isVisible = !portrait
        val margin = Utils.dpToPx(context, if (portrait) 6 else 10)
        (dockLayout.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = margin
            it.marginEnd = margin
            dockLayout.layoutParams = it
        }
        fitRunningApps(runningAppsCount)
    }

    private fun updateQuickSettings() {
        notificationsArea.isVisible =
            sharedPreferences.getBoolean("enable_qs_notif$orientationValue", true)

        // Bluetooth state and the pin button live in the "^" (hidden icons) menu
        bluetoothBtn.isVisible = false
        pinBtn.isVisible = false

        val batteryEnabled =
            sharedPreferences.getBoolean("enable_qs_battery$orientationValue", true)
        batteryBtn.isVisible = batteryEnabled

        val wifiEnabled = sharedPreferences.getBoolean("enable_qs_wifi$orientationValue", true)
        wifiBtn.isVisible = wifiEnabled

        val volumeEnabled = sharedPreferences.getBoolean("enable_qs_vol$orientationValue", true)
        volumeBtn.isVisible = volumeEnabled

        clockArea.isVisible = sharedPreferences.getBoolean("enable_qs_date$orientationValue", true)

        statusArea.isVisible = batteryEnabled || wifiEnabled || volumeEnabled
        fitRunningApps(runningAppsCount)
    }

    /** "^" menu: the tray items that don't fit on the bar (docs/DISENO.md). */
    @SuppressLint("ClickableViewAccessibility")
    private fun showTrayOverflow(anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, preferSecondaryDisplay)
        view.setBackgroundResource(R.drawable.round_rect)
        ColorUtils.applyMainColor(context, sharedPreferences, view, GlassSurface.POPUP)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.START
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.y = Utils.dpToPx(context, 4) + dockHeight
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && view.isAttachedToWindow)
                windowManager.removeView(view)
            false
        }
        val bluetoothOn = bluetoothManager.adapter?.isEnabled ?: false
        val actions = arrayListOf(
            Action(
                if (bluetoothOn) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off,
                getString(R.string.bluetooth)
            ),
            Action(
                if (isPinned) R.drawable.ic_unpin else R.drawable.ic_pin,
                getString(if (isPinned) R.string.taskbar_hide else R.string.taskbar_keep_visible)
            ),
            Action(R.drawable.ic_settings, getString(R.string.taskbar_app_settings))
        )
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        actionsLv.adapter = AppActionsAdapter(context, actions)
        actionsLv.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> openBluetoothSettings()
                1 -> togglePin()
                2 -> launchApp(
                    null, null,
                    Intent(this, MainActivity::class.java)
                )
            }
            if (view.isAttachedToWindow) windowManager.removeView(view)
        }
        windowManager.addView(view, layoutParams)
        PanelAnimator.show(view)
    }

    /** Left block: date, and the next calendar event when the optional permission is granted. */
    private fun updateDateBlock() {
        if (!::dateMainTv.isInitialized) return
        val now = System.currentTimeMillis()
        val locale = Locale.getDefault()
        val event = CalendarUtils.nextEvent(context, now)
        if (event != null) {
            dateMainTv.text = DateFormat.format(
                DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), now
            ).toString().replaceFirstChar { it.titlecase(locale) }
            val time = DateFormat.getTimeFormat(context).format(event.begin)
            nextEventTv.text = "${event.title} · $time"
        } else {
            dateMainTv.text = DateFormat.format("EEEE", now).toString()
                .replaceFirstChar { it.titlecase(locale) }
            nextEventTv.text = DateFormat.format(
                DateFormat.getBestDateTimePattern(locale, "dMMMM"), now
            )
        }
    }

    private fun openCalendarApp() {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            launchApp(null, null, Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI))
        }
    }

    private fun launchAssistant() {
        val assistant = sharedPreferences.getString("app_assistant", "")
        if (assistant!!.isNotEmpty()) launchApp(null, assistant) else {
            try {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun openBluetoothSettings() {
        launchApp(
            null, null,
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        )
    }

    private fun openWiFiSettings() {
        launchApp(
            null, null,
            Intent(Settings.ACTION_WIFI_SETTINGS)
        )
    }

    private fun showSystemWiFiPanel() {
        startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toggleQuickSettingsPanel() {
        if (quickSettingsPanelVisible) hideQuickSettingsPanel() else showQuickSettingsPanel()
    }

    private fun hideQuickSettingsPanel() {
        quickSettings?.release()
        quickSettingsPanel?.let { panel ->
            if (panel.isAttachedToWindow) PanelAnimator.hide(panel) {
                if (panel.isAttachedToWindow) windowManager.removeView(panel)
            }
        }
        quickSettingsPanelVisible = false
        quickSettingsPanel = null
        quickSettings = null
        statusArea.isActivated = false
    }

    /** "Ajustes rápidos": glass panel above the taskbar, on the right (docs/mockup/QuickSettings.png). */
    @SuppressLint("ClickableViewAccessibility")
    private fun showQuickSettingsPanel() {
        hideFlyouts()
        if (appMenuVisible) hideAppMenu()
        val panel = QuickSettingsPanel(
            ContextThemeWrapper(context, R.style.AppTheme_Dock), sharedPreferences, quickSettingsHost
        )
        val bounds = DeviceUtils.getDisplayBounds(context, Display.DEFAULT_DISPLAY)
        val side = Utils.dpToPx(context, PANEL_SIDE_MARGIN_DP)
        val layoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, QUICK_SETTINGS_WIDTH_DP).coerceAtMost(bounds.width() - side * 2),
            WindowManager.LayoutParams.WRAP_CONTENT, context, preferSecondaryDisplay
        )
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.x = side
        layoutParams.y = dockHeight + Utils.dpToPx(context, START_MENU_GAP_DP)
        ColorUtils.applyMainColor(context, sharedPreferences, panel.view, GlassSurface.PANEL)
        // A touch outside closes it, except on the taskbar below (its buttons act on their own)
        panel.view.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && quickSettingsPanelVisible &&
                event.y < view.measuredHeight
            ) hideQuickSettingsPanel()
            false
        }
        windowManager.addView(panel.view, layoutParams)
        PanelAnimator.show(panel.view)
        quickSettingsPanel = panel.view
        quickSettings = panel
        quickSettingsPanelVisible = true
        statusArea.isActivated = true
    }

    private val quickSettingsHost = object : QuickSettingsPanel.Host {
        override fun openFromQuickSettings(intent: Intent) {
            hideQuickSettingsPanel()
            try {
                // System panels (Wi-Fi) are sheets: start them as they are, not in a window
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        override fun openAppSettings() {
            hideQuickSettingsPanel()
            launchApp(null, null, Intent(this@DockService, MainActivity::class.java))
        }
    }

    private fun toggleNotificationCenter() {
        if (notificationCenterVisible) hideNotificationCenter() else showNotificationCenter()
    }

    private fun hideNotificationCenter() {
        val window = notificationCenterWindow
        val center = notificationCenter
        center?.closeReply()
        if (window != null && center != null && window.isAttachedToWindow)
            PanelAnimator.hide(window, listOf(center.notificationsCard)) {
                if (window.isAttachedToWindow) windowManager.removeView(window)
            }
        notificationCenterWindow = null
        notificationCenter = null
        notificationCenterVisible = false
        notificationsArea.isActivated = false
    }

    /** Notifications flyout of the bell: one glass card on the right edge, as tall as the screen allows. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showNotificationCenter() {
        hideFlyouts()
        if (appMenuVisible) hideAppMenu()
        val center = NotificationCenter(
            ContextThemeWrapper(context, R.style.AppTheme_Dock), sharedPreferences,
            notificationCenterHost
        )
        val bounds = DeviceUtils.getDisplayBounds(context, Display.DEFAULT_DISPLAY)
        val side = Utils.dpToPx(context, PANEL_SIDE_MARGIN_DP)
        val gap = Utils.dpToPx(context, START_MENU_GAP_DP)
        val top = DeviceUtils.getStatusBarHeight(context) + gap
        val layoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, NOTIFICATION_CENTER_WIDTH_DP).coerceAtMost(bounds.width() - side * 2),
            (bounds.height() - dockHeight - gap - top).coerceAtLeast(Utils.dpToPx(context, 240)),
            context, preferSecondaryDisplay
        )
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        // The reply field sits near the top: the keyboard must not push the column around
        layoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.x = side
        layoutParams.y = dockHeight + gap
        ColorUtils.applyMainColor(context, sharedPreferences, center.notificationsCard, GlassSurface.PANEL)
        center.view.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && notificationCenterVisible &&
                event.y < view.measuredHeight
            ) hideNotificationCenter()
            false
        }
        windowManager.addView(center.view, layoutParams)
        PanelAnimator.show(center.view, listOf(center.notificationsCard))
        notificationCenterWindow = center.view
        notificationCenter = center
        notificationCenterVisible = true
        notificationsArea.isActivated = true
        updateNotificationCenter()
    }

    private val notificationCenterHost = object : NotificationCenter.Host {
        override fun openNotification(sbn: StatusBarNotification, view: View) {
            hideNotificationCenter()
            onNotificationClicked(sbn, view)
        }

        override fun dismissNotification(sbn: StatusBarNotification) {
            notificationBridge?.mCancelNotification(sbn.key)
        }

        override fun clearAllNotifications() {
            notificationBridge?.cancelAll()
        }

        override fun sendAction(
            sbn: StatusBarNotification, action: Notification.Action, fillIn: Intent?
        ) {
            val intent = action.actionIntent ?: return
            // A button that opens a screen closes the column, like the system shade
            if (NotificationIntents.sendFromTap(context, intent, fillIn) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && intent.isActivity
            ) hideNotificationCenter()
        }

        override fun setTyping(typing: Boolean) {
            val window = notificationCenterWindow ?: return
            if (!window.isAttachedToWindow) return
            val params = window.layoutParams as WindowManager.LayoutParams
            params.flags = if (typing)
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(window, params)
        }
    }

    /** Closes the flyouts on the right (quick settings, notifications, calendar) that are open. */
    private fun hideFlyouts() {
        if (quickSettingsPanelVisible) hideQuickSettingsPanel()
        if (notificationCenterVisible) hideNotificationCenter()
        if (calendarPanelVisible) hideCalendarPanel()
    }

    private fun toggleCalendarPanel() {
        if (calendarPanelVisible) hideCalendarPanel() else showCalendarPanel()
    }

    private fun hideCalendarPanel() {
        val window = calendarPanelWindow
        val panel = calendarPanel
        if (window != null && panel != null && window.isAttachedToWindow)
            PanelAnimator.hide(window, listOf(panel.card)) {
                if (window.isAttachedToWindow) windowManager.removeView(window)
            }
        calendarPanelWindow = null
        calendarPanel = null
        calendarPanelVisible = false
        clockArea.isActivated = false
    }

    /** Calendar flyout of the clock: one glass card above the taskbar, on the right. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showCalendarPanel() {
        hideFlyouts()
        if (appMenuVisible) hideAppMenu()
        val panel = CalendarPanel(
            ContextThemeWrapper(context, R.style.AppTheme_Dock), sharedPreferences, calendarPanelHost
        )
        val bounds = DeviceUtils.getDisplayBounds(context, Display.DEFAULT_DISPLAY)
        val side = Utils.dpToPx(context, PANEL_SIDE_MARGIN_DP)
        val layoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, NOTIFICATION_CENTER_WIDTH_DP).coerceAtMost(bounds.width() - side * 2),
            WindowManager.LayoutParams.WRAP_CONTENT, context, preferSecondaryDisplay
        )
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.x = side
        layoutParams.y = dockHeight + Utils.dpToPx(context, START_MENU_GAP_DP)
        ColorUtils.applyMainColor(context, sharedPreferences, panel.card, GlassSurface.PANEL)
        // A touch outside closes it, except on the taskbar below (its buttons act on their own)
        panel.view.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && calendarPanelVisible &&
                event.y < view.measuredHeight
            ) hideCalendarPanel()
            false
        }
        windowManager.addView(panel.view, layoutParams)
        PanelAnimator.show(panel.view, listOf(panel.card))
        calendarPanelWindow = panel.view
        calendarPanel = panel
        calendarPanelVisible = true
        clockArea.isActivated = true
    }

    private val calendarPanelHost = object : CalendarPanel.Host {
        override fun openCalendar() {
            hideCalendarPanel()
            openCalendarApp()
        }
    }

    /** Current notifications for the center: only shown, never stored or logged (CLAUDE.md). */
    private fun updateNotificationCenter() {
        val center = notificationCenter ?: return
        val ignoredApps = sharedPreferences.getStringSet("ignored_notifications_panel", setOf())!!
        val notifications = try {
            notificationBridge?.notifications?.filterNotNull()
        } catch (_: RemoteException) {
            null
        } ?: emptyList()
        center.update(notifications.filterNot { ignoredApps.contains(it.packageName) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPowerMenu() {
        val layoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 400),
            Utils.dpToPx(context, 120), context, preferSecondaryDisplay
        )
        layoutParams.gravity = Gravity.CENTER
        layoutParams.x = Utils.dpToPx(context, 10)
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        powerMenu = LayoutInflater.from(context).inflate(R.layout.power_menu, null) as LinearLayout
        powerMenu!!.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && powerMenuVisible)
                hidePowerMenu()

            false
        }
        val powerOffBtn = powerMenu!!.findViewById<ImageButton>(R.id.power_off_btn)
        val restartBtn = powerMenu!!.findViewById<ImageButton>(R.id.restart_btn)
        val softRestartBtn = powerMenu!!.findViewById<ImageButton>(R.id.soft_restart_btn)
        val lockBtn = powerMenu!!.findViewById<ImageButton>(R.id.lock_btn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, powerOffBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, restartBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, softRestartBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, lockBtn)
        powerOffBtn.setOnClickListener {
            hidePowerMenu()
            DeviceUtils.shutdown()
        }
        restartBtn.setOnClickListener {
            hidePowerMenu()
            DeviceUtils.reboot()
        }
        softRestartBtn.setOnClickListener {
            hidePowerMenu()
            DeviceUtils.softReboot()
        }
        lockBtn.setOnClickListener {
            hidePowerMenu()
            lockScreen()
        }
        ColorUtils.applyMainColor(context, sharedPreferences, powerMenu!!)
        windowManager.addView(powerMenu, layoutParams)
        PanelAnimator.show(powerMenu!!)
        topRightCorner!!.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_top_right",
                false
            )
        ) View.VISIBLE else View.GONE
        powerMenuVisible = true
    }

    private fun hidePowerMenu() {
        windowManager.removeView(powerMenu)
        powerMenuVisible = false
        powerMenu = null
    }

    fun applyTheme() {
        updateDockShape()
        ColorUtils.applyMainColor(context, sharedPreferences, appMenu!!, appMenuSurface())
        if (!ColorUtils.isGlassTheme(sharedPreferences))
            ColorUtils.applySecondaryColor(context, sharedPreferences, searchEntry)
        ColorUtils.applySecondaryColor(context, sharedPreferences, backBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, homeBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, assistBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, pinBtn)
        if (!ColorUtils.isGlassTheme(sharedPreferences))
            ColorUtils.applySecondaryColor(context, sharedPreferences, powerBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, statusArea)
    }

    private fun updateCorners() {
        topRightCorner!!.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_top_right",
                false
            )
        ) View.VISIBLE else View.GONE
        bottomRightCorner!!.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_bottom_right",
                false
            )
        ) View.VISIBLE else View.GONE
    }

    private fun updateMenuIcon() {
        val iconUri = sharedPreferences.getString("menu_icon_uri", "default")
        if (iconUri == "default") {
            appsBtn.setImageResource(R.drawable.ic_eg_start)
            appsBtn.imageTintList =
                ColorStateList.valueOf(ColorUtils.getAccentColor(sharedPreferences))
        } else {
            appsBtn.imageTintList = null
            try {
                val icon = iconUri?.toUri()
                if (icon != null)
                    appsBtn.setImageURI(icon)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateBatteryBtn() {
        if (sharedPreferences.getBoolean("show_battery_level$orientationValue", false)) {
            batteryReceiver.showLevel = true
            batteryBtn.text = "${batteryReceiver.level}%"
        } else {
            batteryReceiver.showLevel = false
            batteryBtn.text = ""
        }
    }

    /** "Ancladas" of the Start menu (pinned.lst, kept across restarts). */
    private fun loadFavoriteApps() {
        val apps = AppUtils.getPinnedApps(context, AppUtils.PINNED_LIST)
        favoritesGv.adapter = PinnedAppsAdapter(context, apps, this, iconPackUtils)
        favoritesGv.isVisible = apps.isNotEmpty()
        pinnedEmptyTv.isVisible = apps.isEmpty()
        fitPinnedRows()
    }

    /**
     * The pinned grid shows only whole rows (a half-cut row of icons looks broken); the leftover
     * height goes to a spacer above "Recomendado". The grid still scrolls to the other rows.
     */
    private fun fitPinnedRows() {
        val params = favoritesGv.layoutParams as LinearLayout.LayoutParams
        params.height = 0
        params.weight = 1f
        favoritesGv.layoutParams = params
        pinnedSpacer.isVisible = false
        favoritesGv.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                favoritesGv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val child = favoritesGv.getChildAt(0) ?: return
                val margins = child.layoutParams as ViewGroup.MarginLayoutParams
                val rowHeight = child.height + margins.topMargin + margins.bottomMargin
                val room = favoritesGv.height
                if (rowHeight <= 0 || room <= 0) return
                val count = favoritesGv.adapter?.itemCount ?: 0
                val rowsNeeded = (count + START_MENU_COLUMNS - 1) / START_MENU_COLUMNS
                val rows = (room / rowHeight).coerceAtLeast(1).coerceAtMost(rowsNeeded.coerceAtLeast(1))
                val height = rows * rowHeight
                if (height < room) {
                    params.height = height
                    params.weight = 0f
                    favoritesGv.layoutParams = params
                    pinnedSpacer.isVisible = true
                }
            }
        })
    }

    fun takeScreenshot() {
        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    private fun lockScreen() {
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    private fun updateHandlePositionValues() {
        val position = sharedPreferences.getString("handle_position", "start")
        handleLayoutParams.gravity =
            Gravity.BOTTOM or if (position == "start") Gravity.START else Gravity.END
        if (position == "end") {
            dockHandle!!.setBackgroundResource(R.drawable.dock_handle_bg_end)
            dockHandle!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_expand_left,
                0,
                0,
                0
            )
        } else {
            dockHandle!!.setBackgroundResource(R.drawable.dock_handle_bg_start)
            dockHandle!!.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_expand_right,
                0,
                0,
                0
            )
        }
    }

    private fun updateHandlePosition() {
        updateHandlePositionValues()
        windowManager.updateViewLayout(dockHandle, handleLayoutParams)
    }

    private fun updateDockBackgroundColor() {
        // The Escritorio Glass bar floats, so it is always rounded; other themes keep "round_dock"
        val round = sharedPreferences.getBoolean("round_dock", false) ||
                ColorUtils.isGlassTheme(sharedPreferences)
        ColorUtils.applyMainColor(
            context, sharedPreferences, dockLayout,
            if (round) GlassSurface.BAR else GlassSurface.BAR_FLAT
        )
        if (sharedPreferences.getBoolean("override_dock_background_alpha", false))
            applyDockAlpha()
    }

    private fun appMenuSurface() = GlassSurface.MENU

    private fun applyDockAlpha() {
        // With the glass theme opacity comes from the glass tint setting
        if (ColorUtils.isGlassTheme(sharedPreferences)) return
        val alpha = sharedPreferences.getInt("dock_background_alpha", 255)
        dockLayout.background.alpha = alpha
    }

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(motionEvent)
        return false
    }

    override fun onDestroy() {
        //TODO: Unregister all receivers
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        if (::batteryReceiver.isInitialized)
            unregisterReceiver(batteryReceiver)
        if (::soundEventsReceiver.isInitialized)
            unregisterReceiver(soundEventsReceiver)
        timeTickReceiver?.let { unregisterReceiver(it) }
        if (::displayListener.isInitialized && ::displayManager.isInitialized)
            displayManager.unregisterDisplayListener(displayListener)
        // The service can be stopped before onServiceConnected() registered these (e.g. toggled
        // right after an update): don't crash the whole app on the way out
        try {
            connectivityManager.unregisterNetworkCallback(wifiNetworkCallback)
        } catch (_: IllegalArgumentException) {
        }
        removeAllViews()
        GlassController.stop()
        if (quickSettingsPanelVisible) hideQuickSettingsPanel() // stops the flashlight listener
        autoHideHandler.removeCallbacksAndMessages(null)
        DesktopScale.restore(this, sharedPreferences)
        try {
            unbindService(notificationServiceConnection)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    fun performNavAction(key: String) {
        val action = sharedPreferences.getString("${key}_long_action", "none")
        when (action) {
            NAV_LONG_ACTIONS[0] -> return
            NAV_LONG_ACTIONS[1] -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            NAV_LONG_ACTIONS[2] -> launchAssistant()
            NAV_LONG_ACTIONS[3] -> lockScreen()
            NAV_LONG_ACTIONS[4] -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        }
    }

    private fun createViews() {
        context = DeviceUtils.getDisplayContext(this, preferSecondaryDisplay)
        // Windows are always added through the service's own window manager: it carries the
        // accessibility window token, so the bar sits on the bottom edge instead of above the
        // navigation bar
        windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        // With a lowered app scale the taskbar and its panels keep their own, larger scale: views
        // are inflated and measured with this context
        DesktopScale.barDensity(sharedPreferences)?.let { density ->
            // Override only the density: a full copy of the configuration would freeze the
            // orientation and screen size of this moment (the service may start while the phone is
            // still in portrait) and break every size measured with this context
            val config = Configuration()
            config.densityDpi = density
            context = context.createConfigurationContext(config)
        }
        GlassController.start(context)
        orientation = context.resources.configuration.orientation
        densityDpi = resources.configuration.densityDpi // the system's, not the taskbar's
        orientationValue =
            if (orientation == Configuration.ORIENTATION_PORTRAIT) "" else "_landscape"

        createDock()
        createHotCorners()
        createAppMenu()
        notificationLayout = NotificationLayout(
            context,
            sharedPreferences,
            object : NotificationLayout.NotificationCancelListener {
                override fun onNotificationCancel(key: String) {
                    notificationBridge?.mCancelNotification(key)
                }
            })
        windowManager.addView(
            notificationLayout!!.notificationLayout,
            notificationLayout!!.notificationLayoutParams
        )
        applyTheme()
    }

    private fun createDock() {
        dock = LayoutInflater.from(
            androidx.appcompat.view.ContextThemeWrapper(
                context,
                R.style.AppTheme_Dock
            )
        ).inflate(R.layout.dock, null) as HoverInterceptorLayout

        dockLayout = dock!!.findViewById(R.id.dock_layout)
        dockHandle = LayoutInflater.from(context).inflate(R.layout.dock_handle, null) as Button
        appsBtn = dock!!.findViewById(R.id.apps_btn)
        tasksGv = dock!!.findViewById(R.id.apps_lv)
        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        tasksGv.layoutManager = layoutManager
        backBtn = dock!!.findViewById(R.id.back_btn)
        homeBtn = dock!!.findViewById(R.id.home_btn)
        recentBtn = dock!!.findViewById(R.id.recents_btn)
        assistBtn = dock!!.findViewById(R.id.assist_btn)
        notificationBtn = dock!!.findViewById(R.id.notifications_btn)
        pinBtn = dock!!.findViewById(R.id.pin_btn)
        statusArea = dock!!.findViewById(R.id.status_area)
        bluetoothBtn = dock!!.findViewById(R.id.bluetooth_btn)
        wifiBtn = dock!!.findViewById(R.id.wifi_btn)
        volumeBtn = dock!!.findViewById(R.id.volume_btn)
        batteryBtn = dock!!.findViewById(R.id.battery_btn)
        dateTv = dock!!.findViewById(R.id.date_btn)
        searchBtn = dock!!.findViewById(R.id.search_btn)
        trayOverflowBtn = dock!!.findViewById(R.id.tray_overflow_btn)
        dateBlock = dock!!.findViewById(R.id.date_block)
        dateMainTv = dock!!.findViewById(R.id.date_main_tv)
        nextEventTv = dock!!.findViewById(R.id.next_event_tv)
        centerGroup = dock!!.findViewById(R.id.center_group)
        navPanel = dock!!.findViewById(R.id.nav_panel)
        systemTray = dock!!.findViewById(R.id.system_tray)
        notificationsArea = dock!!.findViewById(R.id.notifications_area)
        clockArea = dock!!.findViewById(R.id.clock_area)
        dateLineTv = dock!!.findViewById(R.id.date_line_tv)
        // Re-place the centre group whenever the bar or a side block (date, clock) changes size.
        // fitRunningApps only touches the layout when the result changes, so this settles at once.
        dockLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            fitRunningApps(runningAppsCount)
        }
        dock!!.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                if (dockLayout.isGone) showDock()
            } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) if (dockLayout.isVisible) {
                if (autoHidden) hideDock(TASKBAR_HOVER_HIDE_DELAY_MS, auto = true) else hideDock(500)
            }
            false
        }
        gestureDetector = GestureDetector(context, object : OnSwipeListener() {
            override fun onSwipe(direction: Direction): Boolean {
                if (direction == Direction.DOWN) {
                    if (appMenuVisible) hideAppMenu() else unpinDock()
                } else if (direction == Direction.LEFT) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                return true
            }
        })
        dock!!.setOnTouchListener(this)
        dockLayout.setOnTouchListener(this)
        dockHandle!!.alpha = sharedPreferences.getString("handle_opacity", "0.5")!!.toFloat()
        dockHandle!!.setOnClickListener { pinDock() }
        appsBtn.setOnClickListener { toggleAppMenu() }
        appsBtn.setOnLongClickListener {
            launchApp(
                null, null,
                Intent(Settings.ACTION_APPLICATION_SETTINGS)
            )
            true
        }
        assistBtn.setOnClickListener { launchAssistant() }

        backBtn.setOnClickListener { performGlobalAction(GLOBAL_ACTION_BACK) }
        backBtn.setOnLongClickListener {
            performNavAction("enable_nav_back")
            true
        }
        homeBtn.setOnClickListener { performGlobalAction(GLOBAL_ACTION_HOME) }
        homeBtn.setOnLongClickListener {
            performNavAction("enable_nav_home")
            true
        }
        recentBtn.setOnClickListener { performGlobalAction(GLOBAL_ACTION_RECENTS) }
        recentBtn.setOnLongClickListener {
            performNavAction("enable_nav_recents")
            true
        }

        notificationBtn.setOnClickListener {
            if (sharedPreferences.getBoolean("enable_notif_panel", true)) {
                if (DeviceUtils.isServiceRunning(this, NotificationService::class.java)) {
                    toggleNotificationCenter()
                } else {
                    NotificationPermissionDialog(this, true)
                }
            } else performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        }
        pinBtn.setOnClickListener { togglePin() }
        statusArea.setOnClickListener { toggleQuickSettingsPanel() }
        // The time and date are one button (no listeners on the TextClocks, or a tap on the digits
        // would open the clock app instead): it opens the calendar, like Windows. The bell opens the
        // notifications. Long press on the clock: date and time settings.
        clockArea.setOnClickListener { toggleCalendarPanel() }
        clockArea.setOnLongClickListener {
            launchApp(null, null, Intent(Settings.ACTION_DATE_SETTINGS))
            true
        }
        notificationsArea.setOnClickListener { notificationBtn.performClick() }
        searchBtn.setOnClickListener { toggleAppMenu() }
        trayOverflowBtn.setOnClickListener { showTrayOverflow(it) }
        dateBlock.setOnClickListener { openCalendarApp() }
        updateDateBlock()

        dockHeight = taskbarWindowHeight()
        dockLayoutParams =
            Utils.makeWindowParams(-1, dockHeight, context, preferSecondaryDisplay)
        dockLayoutParams.screenOrientation =
            if (sharedPreferences.getBoolean("lock_landscape", false))
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED


        updateNavigationBar()
        updateQuickSettings()
        updateDockShape()
        updateMenuIcon()
        loadPinnedApps()
        placeRunningApps()

        dockLayoutParams.gravity = Gravity.BOTTOM or Gravity.START
        windowManager.addView(dock, dockLayoutParams)

        //Dock handle
        handleLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 22), -2, context,
            preferSecondaryDisplay
        )
        updateHandlePositionValues()
        windowManager.addView(dockHandle, handleLayoutParams)
    }

    private fun createHotCorners() {
        topRightCorner = Button(context)
        topRightCorner!!.setBackgroundResource(R.drawable.corner_background)
        bottomRightCorner = Button(context)
        bottomRightCorner!!.setBackgroundResource(R.drawable.corner_background)
        topRightCorner!!.setOnHoverListener(HotCornersHoverListener("enable_corner_top_right"))
        bottomRightCorner!!.setOnHoverListener(HotCornersHoverListener("enable_corner_bottom_right"))
        updateCorners()
        val cornersLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 2), -2, context,
            preferSecondaryDisplay
        )
        cornersLayoutParams.gravity = Gravity.TOP or Gravity.END
        windowManager.addView(topRightCorner, cornersLayoutParams)
        cornersLayoutParams.gravity = Gravity.BOTTOM or Gravity.END
        windowManager.addView(bottomRightCorner, cornersLayoutParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createAppMenu() {
        appMenu = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
            .inflate(R.layout.start_menu, null) as LinearLayout
        val menu = appMenu!!
        searchEntry = menu.findViewById(R.id.search_entry)
        searchEt = menu.findViewById(R.id.menu_et)
        powerBtn = menu.findViewById(R.id.power_btn)
        appsGv = menu.findViewById(R.id.menu_applist_lv)
        appsGv.layoutManager = LinearLayoutManager(context)
        favoritesGv = menu.findViewById(R.id.fav_applist_lv)
        favoritesGv.layoutManager = GridLayoutManager(context, START_MENU_COLUMNS)
        startHome = menu.findViewById(R.id.start_home)
        startAll = menu.findViewById(R.id.start_all)
        allAppsTitleTv = menu.findViewById(R.id.all_apps_title_tv)
        pinnedEmptyTv = menu.findViewById(R.id.pinned_empty_tv)
        pinnedSpacer = menu.findViewById(R.id.pinned_spacer)
        noResultsTv = menu.findViewById(R.id.start_no_results_tv)
        recommendedSection = menu.findViewById(R.id.recommended_section)
        recentLv = menu.findViewById(R.id.recent_lv)
        recentLv.layoutManager = GridLayoutManager(context, 2)
        recentEmptyTv = menu.findViewById(R.id.recent_empty_tv)
        moreRecentBtn = menu.findViewById(R.id.more_recent_btn)
        startUserNameTv = menu.findViewById(R.id.user_name_tv)
        startAvatarIv = menu.findViewById(R.id.avatar_iv)
        startAvatarInitialTv = menu.findViewById(R.id.avatar_initial_tv)

        powerBtn.setOnClickListener { showStartPowerMenu(it) }
        menu.findViewById<View>(R.id.user_btn).setOnClickListener {
            launchApp(null, null, Intent(this, MainActivity::class.java))
        }
        menu.findViewById<View>(R.id.all_apps_btn).setOnClickListener { showStartAll(searching = false) }
        menu.findViewById<View>(R.id.start_back_btn).setOnClickListener {
            if (searchEt.text.isNotEmpty()) searchEt.setText("") else showStartHome()
        }
        moreRecentBtn.setOnClickListener {
            showAllRecent = !showAllRecent
            loadRecentApps()
        }

        // Search only looks at the installed apps: what is typed never leaves the phone
        searchEt.addTextChangedListener { text -> onStartSearch(text?.toString() ?: "") }
        searchEt.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                launchFirstStartResult()
                true
            } else false
        }
        searchEt.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN)
                appsGv.requestFocus()
            false
        }

        updateAppMenu()

        // A touch outside closes the menu, except on the taskbar below it (its buttons act on their own)
        menu.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && appMenuVisible &&
                event.y < view.measuredHeight
            ) hideAppMenu()
            false
        }
    }

    private fun restartUI() {
        removeAllViews()
        createViews()
        // The battery receiver outlives the views: hand it the new battery icon
        if (::batteryReceiver.isInitialized) batteryReceiver.attach(batteryBtn)
        updateBatteryBtn()
        // The new bell starts without its counter until the next notification: fill it now
        updateNotificationCount()
        if (sharedPreferences.getBoolean("pin_dock", true))
            pinDock()
        else
            Toast.makeText(context, R.string.start_message, Toast.LENGTH_LONG).show()
    }

    fun showFirstDockHideDialog(activationMethod: String) {
        val dialog = DockDialog(context, true)
        dialog.setTitle(R.string.dock_hidden)
        val message =
            if (activationMethod == "swipe") R.string.dock_hidden_message_swipe else R.string.dock_hidden_message_handle
        dialog.setMessage(message)
        dialog.setPositiveButton(R.string.close, null)
        dialog.setCancelable(false)
        dialog.show()
        sharedPreferences.edit { putBoolean("first_hide", false) }
    }

    private fun updateWiFiStatus() {
        val enabled = wifiManager.isWifiEnabled
        quickSettings?.refresh()
        wifiBtn.setImageResource(if (enabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off)
    }

    private fun updateBluetoothStatus() {
        val enabled = bluetoothManager.adapter?.isEnabled ?: false
        quickSettings?.refresh()
        bluetoothBtn.setImageResource(if (enabled) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off)
    }

    private fun removeAllViews() {
        appMenu?.let { PanelAnimator.cancel(it) }
        // One by one: a window that is not shown (the closed Start menu) must not stop the others
        listOfNotNull(
            dock, dockHandle, appMenu, topRightCorner, bottomRightCorner,
            notificationLayout?.notificationLayout
        ).forEach { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: IllegalArgumentException) {
                // Not added right now
            }
        }

        dock = null
        dockHandle = null
        appMenu = null
        topRightCorner = null
        bottomRightCorner = null
        notificationLayout = null
    }

    inner class HotCornersHoverListener(val key: String) : View.OnHoverListener {
        override fun onHover(
            v: View?,
            event: MotionEvent
        ): Boolean {
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                val handler = Handler(mainLooper)
                handler.postDelayed({
                    if (topRightCorner!!.isHovered)
                        performNavAction(key)
                }, sharedPreferences.getString("hot_corners_delay", "300")!!.toInt().toLong())
            }
            return false
        }
    }

    private fun bindNotificationService() {
        if (notificationBridge != null)
            return
        bindService(
            Intent(
                this,
                NotificationService::class.java
            ).setAction(ACTION_BIND_NOTIFICATION_SERVICE), notificationServiceConnection,
            BIND_AUTO_CREATE
        )
    }

    private val notificationServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            p0: ComponentName?,
            p1: IBinder?
        ) {
            notificationBridge = INotificationServiceBridge.Stub.asInterface(p1)
            notificationBridge?.registerCallback(object : INotificationCallback.Stub() {
                override fun onNotificationPosted(sbn: StatusBarNotification?) {
                    if (notificationCenterVisible)
                        updateNotificationCenter()
                    updateNotificationCount()
                    notificationLayout?.let {
                        it.updateNotificationLayout(sbn!!)
                        windowManager.updateViewLayout(
                            it.notificationLayout,
                            it.notificationLayoutParams
                        )
                    }
                }

                override fun onNotificationRemoved(sbn: StatusBarNotification?) {
                    if (notificationCenterVisible)
                        updateNotificationCenter()
                    updateNotificationCount()
                }

            })
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            notificationBridge = null
        }

    }

    override fun onNotificationClicked(sbn: StatusBarNotification, item: View) {
        val notification = sbn.notification
        val intent = notification.contentIntent ?: return
        // Like the system shade: only notifications marked "auto cancel" go away when opened
        if (NotificationIntents.sendFromTap(context, intent) && sbn.isClearable &&
            notification.flags and Notification.FLAG_AUTO_CANCEL != 0
        )
            notificationBridge?.mCancelNotification(sbn.key)
    }

    override fun onNotificationLongClicked(notification: StatusBarNotification, item: View) {
        item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val dialog = DockDialog(context, true)
        dialog.setTitle(R.string.hide_notifications)
        dialog.setMessage(R.string.hide_notification_panel)
        dialog.setNegativeButton(R.string.cancel, null)
        dialog.setPositiveButton(
            R.string.hide
        ) { dialog, which ->
            val savedApps = sharedPreferences.getStringSet(
                "ignored_notifications_panel",
                setOf()
            )!!
            val ignoredApps = mutableSetOf<String>()
            ignoredApps.addAll(savedApps)
            ignoredApps.add(notification.packageName)
            sharedPreferences.edit { putStringSet("ignored_notifications_panel", ignoredApps) }
            Toast.makeText(
                this@DockService,
                R.string.silenced_notifications,
                Toast.LENGTH_LONG
            )
                .show()
        }
        dialog.show()
    }

    override fun onNotificationCancelClicked(notification: StatusBarNotification, item: View) {
        notificationBridge?.mCancelNotification(notification.key)
    }

    private fun updateNotificationCount() {
        val count = notificationBridge?.notificationCount ?: 0
        notificationBtn.isVisible = count > 0
        notificationBtn.text = if (count > 99) "99+" else count.toString()
        notificationBtn.background?.setTint(ColorUtils.getAccentColor(sharedPreferences))
    }

    private fun showSettingsPermissionDialog() {
        val dialog = DockDialog(context, true)
        dialog.setTitle(R.string.missing_permission)
        dialog.setMessage(R.string.write_settings_desc)
        dialog.setNegativeButton(R.string.cancel, null)
        dialog.setPositiveButton(
            R.string.manage
        ) { dialog, which ->
            launchApp(
                null, null, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData("package:${packageName}".toUri())
            )
        }
        dialog.show()
    }

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            CoroutineScope(Dispatchers.Main).launch {
                if (quickSettingsPanelVisible)
                    updateWiFiStatus()
            }
        }

        override fun onLost(network: Network) {
            CoroutineScope(Dispatchers.Main).launch {
                if (quickSettingsPanelVisible)
                    updateWiFiStatus()
            }
        }

    }
}
