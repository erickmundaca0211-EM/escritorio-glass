package cu.axel.smartdock.components

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cu.axel.smartdock.R
import cu.axel.smartdock.models.AppTask
import cu.axel.smartdock.utils.Utils

/**
 * Window switcher, like Alt+Tab in Windows 11 but with icons instead of thumbnails: the open windows
 * in a row, most recently used first, and the name of the selected one below. The keyboard moves the
 * selection (DockService picks it when Alt is let go); a click on an icon picks that window at once.
 */
class TaskSwitcher(
    private val context: Context,
    private val tasks: List<AppTask>,
    icon: (AppTask) -> Drawable,
    private val host: Host
) {
    interface Host {
        fun switchTo(task: AppTask)
    }

    val view: View = LayoutInflater.from(context).inflate(R.layout.task_switcher, null)
    val card: View = view.findViewById(R.id.switcher_card)
    private val scroll: HorizontalScrollView = view.findViewById(R.id.switcher_scroll)
    private val label: TextView = view.findViewById(R.id.switcher_label)
    private val items = ArrayList<View>()

    /** Index of the selected window in the list, or -1 when there is none. */
    private var selected = -1

    val selectedTask: AppTask? get() = tasks.getOrNull(selected)

    init {
        val row = view.findViewById<LinearLayout>(R.id.switcher_items)
        val size = Utils.dpToPx(context, ITEM_DP)
        val iconSize = Utils.dpToPx(context, ICON_DP)
        val gap = Utils.dpToPx(context, GAP_DP)
        tasks.forEachIndexed { index, task ->
            val item = FrameLayout(context).apply {
                setBackgroundResource(R.drawable.task_switcher_item_bg)
                contentDescription = task.name
                setOnClickListener { host.switchTo(task) }
            }
            item.addView(
                ImageView(context).apply { setImageDrawable(icon(task)) },
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            )
            row.addView(item, LinearLayout.LayoutParams(size, size).apply {
                if (index > 0) marginStart = gap
            })
            items.add(item)
        }
        if (tasks.isEmpty()) label.setText(R.string.task_switcher_empty)
    }

    /** Selects the window at [index], going round at both ends, and shows its name. */
    fun select(index: Int) {
        if (tasks.isEmpty()) return
        selected = Math.floorMod(index, tasks.size)
        items.forEachIndexed { i, item -> item.isSelected = i == selected }
        label.text = tasks[selected].name
        // With more windows than fit on screen, keep the selected one in the middle of the row
        val item = items[selected]
        scroll.post { scroll.smoothScrollTo(item.left - (scroll.width - item.width) / 2, 0) }
    }

    fun move(step: Int) = select(selected + step)

    private companion object {
        const val ITEM_DP = 76
        const val ICON_DP = 46
        const val GAP_DP = 8
    }
}
