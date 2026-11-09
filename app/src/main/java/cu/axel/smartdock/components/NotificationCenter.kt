package cu.axel.smartdock.components

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.service.notification.StatusBarNotification
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cu.axel.smartdock.R
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.StartMenuUtils
import cu.axel.smartdock.utils.SwipeDismissTouchListener
import cu.axel.smartdock.utils.Utils
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * Escritorio Glass notifications and calendar column (docs/DISENO.md, "Notificaciones y calendario").
 *
 * Notifications are grouped by app and only shown or dismissed: nothing is stored or sent anywhere
 * (CLAUDE.md). The calendar is a plain month view starting on Sunday; it reads no events.
 */
class NotificationCenter(
    private val context: Context,
    sharedPreferences: SharedPreferences,
    private val host: Host
) {
    interface Host {
        fun openNotification(sbn: StatusBarNotification, view: View)
        fun dismissNotification(sbn: StatusBarNotification)
        fun clearAllNotifications()
        fun openCalendar()
    }

    private class Group(val packageName: String, val notifications: List<StatusBarNotification>)

    val view: LinearLayout =
        LayoutInflater.from(context).inflate(R.layout.notification_center, null) as LinearLayout
    val notificationsCard: View = view.findViewById(R.id.nc_notifications_card)
    val calendarCard: View = view.findViewById(R.id.nc_calendar_card)

    private val list: RecyclerView = view.findViewById(R.id.nc_list)
    private val empty: TextView = view.findViewById(R.id.nc_empty)
    private val clearAll: TextView = view.findViewById(R.id.nc_clear_all)
    private val adapter = GroupAdapter()
    private val accent = ColorUtils.getAccentColor(sharedPreferences)
    private val shownMonth: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private val dayCells = ArrayList<TextView>()

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        clearAll.setOnClickListener { host.clearAllNotifications() }
        setUpCalendar()
    }

    /** Shows the current notifications, newest app first (group summaries are skipped). */
    fun update(notifications: List<StatusBarNotification>) {
        val groups = notifications
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .groupBy { it.packageName }
            .map { (pkg, items) -> Group(pkg, items) }
        adapter.groups = groups
        adapter.notifyDataSetChanged()
        list.isVisible = groups.isNotEmpty()
        empty.isVisible = groups.isEmpty()
        clearAll.isVisible = groups.any { group -> group.notifications.any { it.isClearable } }
    }

    // ---- notifications

    private inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.Holder>() {
        var groups: List<Group> = emptyList()

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.group_icon)
            val app: TextView = itemView.findViewById(R.id.group_app)
            val time: TextView = itemView.findViewById(R.id.group_time)
            val items: LinearLayout = itemView.findViewById(R.id.group_items)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(context).inflate(R.layout.notification_group_entry, parent, false)
        )

        override fun getItemCount() = groups.size

        // Taps still reach the row: the swipe listener only takes over sideways moves
        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val group = groups[position]
            val pm = context.packageManager
            try {
                val info = pm.getApplicationInfo(group.packageName, 0)
                holder.icon.setImageDrawable(pm.getApplicationIcon(info))
                holder.app.text = pm.getApplicationLabel(info)
            } catch (_: PackageManager.NameNotFoundException) {
                holder.icon.setImageDrawable(null)
                holder.app.text = group.packageName
            }
            holder.time.text =
                StartMenuUtils.relativeTime(context, group.notifications.first().postTime)
            holder.items.removeAllViews()
            for (sbn in group.notifications) {
                val row = LayoutInflater.from(context)
                    .inflate(R.layout.notification_item_entry, holder.items, false)
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                row.findViewById<TextView>(R.id.item_title).apply {
                    this.text = title ?: holder.app.text
                }
                row.findViewById<TextView>(R.id.item_text).apply {
                    this.text = text
                    isVisible = !text.isNullOrBlank()
                }
                row.setOnClickListener { host.openNotification(sbn, it) }
                // Swiping sideways closes it, like the system shade (ongoing ones spring back)
                row.setOnTouchListener(
                    SwipeDismissTouchListener(canDismiss = sbn.isClearable, collapse = true) {
                        host.dismissNotification(sbn)
                    }
                )
                row.findViewById<View>(R.id.item_dismiss).apply {
                    isVisible = sbn.isClearable
                    setOnClickListener { host.dismissNotification(sbn) }
                }
                holder.items.addView(row)
            }
        }
    }

    // ---- calendar

    private fun setUpCalendar() {
        val locale = Locale.getDefault()
        val weekdays = view.findViewById<LinearLayout>(R.id.cal_weekdays)
        // Week starts on Sunday (docs/DISENO.md); narrow names give "D L M X J V S" in Spanish
        val order = listOf(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
        )
        for (day in order) {
            weekdays.addView(TextView(context).apply {
                // Spanish calendars write miércoles as "X" so it differs from martes ("M")
                text = if (locale.language == "es" && day == DayOfWeek.WEDNESDAY) "X"
                else day.getDisplayName(TextStyle.NARROW, locale).uppercase(locale)
                gravity = Gravity.CENTER
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(context, R.color.glass_text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            })
        }
        val grid = view.findViewById<GridLayout>(R.id.cal_days)
        val cellSize = Utils.dpToPx(context, 24)
        repeat(42) {
            val cell = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.glass_text_primary))
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            params.width = cellSize
            params.height = cellSize
            params.setGravity(Gravity.CENTER)
            params.topMargin = Utils.dpToPx(context, 1)
            grid.addView(cell, params)
            dayCells.add(cell)
        }
        view.findViewById<View>(R.id.cal_prev).setOnClickListener { moveMonth(-1) }
        view.findViewById<View>(R.id.cal_next).setOnClickListener { moveMonth(1) }
        view.findViewById<View>(R.id.cal_title).setOnClickListener { host.openCalendar() }
        renderMonth()
    }

    private fun moveMonth(delta: Int) {
        shownMonth.add(Calendar.MONTH, delta)
        renderMonth()
    }

    private fun renderMonth() {
        val locale = Locale.getDefault()
        val today = Calendar.getInstance()
        val sameMonth = today.get(Calendar.YEAR) == shownMonth.get(Calendar.YEAR) &&
                today.get(Calendar.MONTH) == shownMonth.get(Calendar.MONTH)
        val pattern = DateFormat.getBestDateTimePattern(locale, if (sameMonth) "EEEEdMMMM" else "MMMMyyyy")
        view.findViewById<TextView>(R.id.cal_title).text =
            DateFormat.format(pattern, if (sameMonth) today else shownMonth)

        val offset = shownMonth.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val days = shownMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = (offset + days + 6) / 7
        val onAccent = ContextCompat.getColor(context, R.color.on_accent)
        val primary = ContextCompat.getColor(context, R.color.glass_text_primary)
        dayCells.forEachIndexed { index, cell ->
            val day = index - offset + 1
            cell.isVisible = index < rows * 7
            cell.text = if (day in 1..days) day.toString() else ""
            val isToday = sameMonth && day == today.get(Calendar.DAY_OF_MONTH)
            if (isToday) {
                cell.background = ContextCompat.getDrawable(context, R.drawable.calendar_today_bg)
                    ?.mutate()?.apply { setTint(accent) }
                cell.setTextColor(onAccent)
                cell.setTypeface(null, Typeface.BOLD)
            } else {
                cell.background = null
                cell.setTextColor(primary)
                cell.setTypeface(null, Typeface.NORMAL)
            }
        }
    }
}
