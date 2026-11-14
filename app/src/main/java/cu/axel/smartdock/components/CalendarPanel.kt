package cu.axel.smartdock.components

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import cu.axel.smartdock.R
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.Utils
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * Calendar flyout of the taskbar clock (docs/DISENO.md, "Notificaciones y calendario"): today's date
 * and a month view starting on Sunday. It reads no events.
 */
class CalendarPanel(
    private val context: Context,
    private val sharedPreferences: SharedPreferences,
    private val host: Host
) {
    interface Host {
        fun openCalendar()
    }

    val view: View = LayoutInflater.from(context).inflate(R.layout.calendar_panel, null)
    val card: View = view.findViewById(R.id.cal_card)

    private val shownMonth: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private val dayCells = ArrayList<TextView>()

    init {
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
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.glass_text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            })
        }
        val grid = view.findViewById<GridLayout>(R.id.cal_days)
        val cellSize = Utils.dpToPx(context, CELL_DP)
        repeat(42) {
            val cell = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.glass_text_primary))
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            params.width = cellSize
            params.height = cellSize
            params.setGravity(Gravity.CENTER)
            params.topMargin = Utils.dpToPx(context, 2)
            grid.addView(cell, params)
            dayCells.add(cell)
        }
        view.findViewById<View>(R.id.cal_prev).setOnClickListener { moveMonth(-1) }
        view.findViewById<View>(R.id.cal_next).setOnClickListener { moveMonth(1) }
        view.findViewById<View>(R.id.cal_title).setOnClickListener { host.openCalendar() }
        renderMonth()
    }

    /** The panel is made once and kept: each time it opens it goes back to today's month. */
    fun showToday() {
        val today = Calendar.getInstance()
        shownMonth.set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), 1)
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
        val accent = ColorUtils.getAccentColor(sharedPreferences)
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

    private companion object {
        /** Size of a day: the calendar has the whole flyout now, so days are easier to tap. */
        const val CELL_DP = 30
    }
}
