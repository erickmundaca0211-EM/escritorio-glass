package cu.axel.smartdock.activities

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cu.axel.smartdock.R
import cu.axel.smartdock.adapters.AppActionsAdapter
import cu.axel.smartdock.glass.GlassSurface
import cu.axel.smartdock.models.Action
import cu.axel.smartdock.models.App
import cu.axel.smartdock.services.ACTION_LAUNCH_APP
import cu.axel.smartdock.services.DESKTOP_APP_PINNED
import cu.axel.smartdock.services.DESKTOP_CLOSE
import cu.axel.smartdock.services.DOCK_SERVICE_ACTION
import cu.axel.smartdock.services.taskbarClearancePx
import cu.axel.smartdock.utils.AppUtils
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.IconPackUtils
import cu.axel.smartdock.utils.PanelAnimator

/**
 * Escritorio Glass's own desktop (Fase 6, step 7): the wallpaper with small app icons in columns from
 * the top left, like Windows. It is not the phone's home screen (HyperOS would then take its gestures
 * away): it is an ordinary full-screen window that DockService keeps right above the HyperOS home, so
 * apps open over it and it shows again when they go away. A click opens an app; a right click or a
 * long press opens the glass menu of an icon or of the desktop itself.
 */
class DesktopActivity : AppCompatActivity() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var icons: RecyclerView
    private lateinit var grid: GridLayoutManager
    private var iconPack: IconPackUtils? = null
    private var menu: PopupWindow? = null

    /** Where the last press on an icon was, on screen: its menu opens there. */
    private var pressX = 0f
    private var pressY = 0f

    /** The press going on started on an icon (the icon handles it) rather than on the empty desktop. */
    private var pressOnIcon = false

    private val serviceMessages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("action")) {
                DESKTOP_APP_PINNED -> loadIcons()
                DESKTOP_CLOSE -> finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_desktop)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (sharedPreferences.getString("icon_pack", "")!!.isNotEmpty()) iconPack = IconPackUtils(this)
        icons = findViewById(R.id.desktop_icons)
        // As many rows as fit: icons fill each column from the top, then the next one to the right
        grid = GridLayoutManager(this, 1, RecyclerView.HORIZONTAL, false)
        icons.layoutManager = grid
        val cellHeight = resources.getDimensionPixelSize(R.dimen.desktop_cell_height)
        icons.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val rows = ((view.height - view.paddingTop - view.paddingBottom) / cellHeight).coerceAtLeast(1)
            if (rows != grid.spanCount) view.post { grid.spanCount = rows }
        }
        // The wallpaper goes under the status bar and the camera hole; the icons stay clear of them
        val top = icons.paddingTop
        val start = icons.paddingStart
        val end = icons.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(icons) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = start + bars.left, top = top + bars.top, right = end + bars.right)
            insets
        }
        watchEmptyDesktop()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back on the desktop goes nowhere, like Windows (it must not show the HyperOS home)
            override fun handleOnBackPressed() {}
        })
        ContextCompat.registerReceiver(
            this, serviceMessages, IntentFilter(DOCK_SERVICE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        pinDefaultAppsOnce()
    }

    override fun onResume() {
        super.onResume()
        // Desktop mode stopped while this was away: there is nothing to show it over anymore
        if (!DeviceUtils.isAccessibilityServiceEnabled(this)) {
            finishAndRemoveTask()
            return
        }
        // Here and not once: the taskbar height may have changed, or an app been uninstalled, meanwhile
        icons.updatePadding(bottom = taskbarClearance())
        loadIcons()
    }

    /** Room kept free under the icons and the menus for the floating taskbar, plus a small gap. */
    private fun taskbarClearance(): Int =
        taskbarClearancePx(this, sharedPreferences) + resources.getDimensionPixelSize(R.dimen.desktop_taskbar_gap)

    override fun onPause() {
        super.onPause()
        menu?.dismiss()
    }

    override fun onDestroy() {
        unregisterReceiver(serviceMessages)
        menu?.dismiss()
        super.onDestroy()
    }

    private fun loadIcons() {
        icons.adapter = IconAdapter(AppUtils.getPinnedApps(this, AppUtils.DESKTOP_LIST))
    }

    /** The first time, a few everyday apps so the desktop does not start empty (removable as any other). */
    private fun pinDefaultAppsOnce() {
        if (sharedPreferences.getBoolean(KEY_DEFAULT_PINS_DONE, false)) return
        sharedPreferences.edit { putBoolean(KEY_DEFAULT_PINS_DONE, true) }
        if (AppUtils.getPinnedApps(this, AppUtils.DESKTOP_LIST).isNotEmpty()) return
        // The browser that opens web links, the file manager, Settings and the app store
        listOfNotNull(
            appFor(Intent(Intent.ACTION_VIEW, "http://".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)),
            appFor(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_FILES))
                ?: FILE_MANAGERS.firstOrNull { canLaunch(it) },
            appFor(Intent(Settings.ACTION_SETTINGS)),
            appFor(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET))
        ).distinct().forEach {
            AppUtils.pinApp(this, App(it, it, ColorDrawable(Color.TRANSPARENT)), AppUtils.DESKTOP_LIST)
        }
    }

    /** The app [intent] opens by default or, with no default (the chooser would ask), the first one able to. */
    private fun appFor(intent: Intent): String? {
        val default = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        if (default != null && canLaunch(default)) return default
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .firstOrNull { canLaunch(it) }
    }

    /** It has an icon in the app list (the chooser and other system pieces have none). */
    private fun canLaunch(app: String) = packageManager.getLaunchIntentForPackage(app) != null

    /** Opens [app] through the desktop service, which knows the launch modes and the hidden windows. */
    private fun launch(app: String, mode: String? = null) {
        sendBroadcast(
            Intent(LAUNCHER_ACTION)
                .setPackage(packageName)
                .putExtra("action", ACTION_LAUNCH_APP)
                .putExtra("mode", mode)
                .putExtra("app", app)
        )
    }

    /** Always in a task of its own: on the desktop's task it would be hidden from the taskbar. */
    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    // ---- Menus: glass, at the pointer, closed by a click anywhere else

    /** A right click or a long press on the empty desktop opens its menu. */
    private fun watchEmptyDesktop() {
        val longPress = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) = showDesktopMenu(e.rawX, e.rawY)
        })
        icons.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN)
                    pressOnIcon = rv.findChildViewUnder(e.x, e.y) != null
                if (pressOnIcon) return false
                if (e.actionMasked == MotionEvent.ACTION_DOWN && e.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                    showDesktopMenu(e.rawX, e.rawY)
                    return true
                }
                longPress.onTouchEvent(e)
                return false
            }
        })
    }

    private fun showDesktopMenu(x: Float, y: Float) {
        val actions = arrayListOf(
            Action(R.drawable.ic_wallpaper, getString(R.string.change_wallpaper)),
            Action(R.drawable.ic_fullscreen, getString(R.string.display_settings)),
            Action(R.drawable.ic_eg_settings, getString(R.string.desktop_settings))
        )
        showMenu(actions, x, y) { action ->
            when (action.text) {
                getString(R.string.change_wallpaper) -> open(
                    Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.change_wallpaper))
                )

                getString(R.string.display_settings) -> open(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                getString(R.string.desktop_settings) -> open(Intent(this, MainActivity::class.java))
            }
        }
    }

    private fun showIconMenu(app: App) {
        val actions = arrayListOf(
            Action(R.drawable.ic_apps_menu, getString(R.string.desktop_open)),
            Action(R.drawable.ic_launch_mode, getString(R.string.open_as)),
            Action(R.drawable.ic_info, getString(R.string.desktop_app_info)),
            Action(R.drawable.ic_remove_from_desktop, getString(R.string.desktop_remove))
        )
        if (!AppUtils.isSystemApp(this, app.packageName))
            actions += Action(R.drawable.ic_uninstall, getString(R.string.uninstall))
        showMenu(actions, pressX, pressY) { action ->
            when (action.text) {
                getString(R.string.desktop_open) -> launch(app.packageName)
                getString(R.string.open_as) -> showOpenAsMenu(app)
                getString(R.string.desktop_app_info) -> open(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${app.packageName}".toUri())
                )

                getString(R.string.desktop_remove) -> {
                    AppUtils.unpinApp(this, app.packageName, AppUtils.DESKTOP_LIST)
                    loadIcons()
                }

                getString(R.string.uninstall) -> open(
                    Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:${app.packageName}".toUri())
                )
            }
        }
    }

    /** "Abrir en…": the same launch modes as the Start menu. */
    private fun showOpenAsMenu(app: App) {
        val modes = linkedMapOf(
            Action(R.drawable.ic_standard, getString(R.string.standard)) to "standard",
            Action(R.drawable.ic_maximized, getString(R.string.maximized)) to "maximized",
            Action(R.drawable.ic_portrait, getString(R.string.portrait)) to "portrait",
            Action(R.drawable.ic_fullscreen, getString(R.string.fullscreen)) to "fullscreen"
        )
        showMenu(ArrayList(modes.keys), pressX, pressY) { action -> launch(app.packageName, modes[action]) }
    }

    /** A glass menu at ([x], [y]) on screen, kept inside it and above the taskbar. */
    private fun showMenu(actions: ArrayList<Action>, x: Float, y: Float, onAction: (Action) -> Unit) {
        menu?.dismiss()
        val content = LayoutInflater.from(this).inflate(R.layout.task_list, null)
        ColorUtils.applyMainColor(this, sharedPreferences, content, GlassSurface.POPUP)
        val list = content.findViewById<ListView>(R.id.tasks_lv)
        val adapter = AppActionsAdapter(this, actions)
        list.adapter = adapter
        // As wide as its longest entry, like a Windows menu: none is cut into two lines
        val widest = actions.indices.maxOf { position ->
            adapter.getView(position, null, list)
                .apply { measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED) }
                .measuredWidth
        }
        list.layoutParams = list.layoutParams.apply { width = maxOf(width, widest) }
        val popup = PopupWindow(
            content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        list.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onAction(actions[position])
        }
        val root = icons.rootView
        content.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST)
        )
        val maxX = (root.width - content.measuredWidth).coerceAtLeast(0)
        val maxY = (root.height - icons.paddingBottom - content.measuredHeight).coerceAtLeast(0)
        popup.showAtLocation(icons, Gravity.TOP or Gravity.START, x.toInt().coerceIn(0, maxX), y.toInt().coerceIn(0, maxY))
        PanelAnimator.show(content)
        menu = popup
    }

    // ---- Icons

    private class IconHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.desktop_icon_iv)
        val name: TextView = view.findViewById(R.id.desktop_icon_tv)
    }

    private inner class IconAdapter(private val apps: List<App>) : RecyclerView.Adapter<IconHolder>() {
        override fun getItemCount() = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            IconHolder(LayoutInflater.from(parent.context).inflate(R.layout.desktop_icon, parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: IconHolder, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(iconPack?.getAppThemedIcon(app.packageName) ?: app.icon)
            holder.name.text = app.name
            holder.itemView.contentDescription = app.name
            // One click opens it, as Escritorio Glass does everywhere (no double click)
            holder.itemView.setOnClickListener { launch(app.packageName) }
            holder.itemView.setOnLongClickListener {
                showIconMenu(app)
                true
            }
            holder.itemView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    pressX = event.rawX
                    pressY = event.rawY
                    // Right click: its menu at once, like Windows
                    if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                        showIconMenu(app)
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }
    }

    private companion object {
        const val KEY_DEFAULT_PINS_DONE = "desktop_default_pins_done"

        /** File managers that do not declare CATEGORY_APP_FILES, HyperOS's own among them. */
        val FILE_MANAGERS = listOf(
            "com.mi.android.globalFileexplorer", "com.android.fileexplorer", "com.google.android.apps.nbu.files",
            "com.sec.android.app.myfiles", "com.google.android.documentsui", "com.android.documentsui"
        )
    }
}
