package cu.axel.smartdock.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import cu.axel.smartdock.R
import cu.axel.smartdock.models.AppTask
import cu.axel.smartdock.utils.Utils
import kotlin.math.abs
import kotlin.math.sign

/**
 * Window switcher, like Alt+Tab in Windows 11 but with icons instead of thumbnails: the open windows
 * in a row, most recently used first, and the name of the selected one below. The keyboard moves the
 * selection (DockService picks it when Alt is let go); a click on an icon picks that window at once.
 * When windows can be closed (Shizuku): an icon thrown up or down out of the row closes its window,
 * like swiping away a recent app; the mouse also gets a × on the icon under it; Supr closes the
 * selected one and "Close all" closes them all at once.
 */
class TaskSwitcher(
    private val context: Context,
    tasks: List<AppTask>,
    icon: (AppTask) -> Drawable,
    /** Windows can be closed from here. */
    private val canClose: Boolean,
    private val host: Host
) {
    interface Host {
        fun switchTo(task: AppTask)

        /** Closes [task]'s window; false when it could not. */
        fun close(task: AppTask): Boolean

        /** [switcher] has nothing left to show: it goes away, unless another one took its place. */
        fun dismiss(switcher: TaskSwitcher)
    }

    /** A window in the row: its button and, when windows can be closed, its ×. */
    private class Item(val task: AppTask, val view: FrameLayout, val closeButton: ImageView?)

    val view: View = LayoutInflater.from(context).inflate(R.layout.task_switcher, null)
    val card: View = view.findViewById(R.id.switcher_card)
    private val scroll: HorizontalScrollView = view.findViewById(R.id.switcher_scroll)
    private val row: LinearLayout = view.findViewById(R.id.switcher_items)
    private val label: TextView = view.findViewById(R.id.switcher_label)
    private val closeAllButton: View = view.findViewById(R.id.switcher_close_all)
    private val items = ArrayList<Item>()

    /** Index of the selected window in the list, or -1 when there is none. */
    private var selected = -1

    /** The window under the mouse, if any. */
    private var hovered: Item? = null

    /** An icon is being pressed (and then opened or closed): the name below no longer follows the pointer. */
    private var labelLocked = false

    /** The window whose × is being pressed. */
    private var pressedClose: Item? = null

    /** The icon being thrown, where its press started on screen and how fast it moves. */
    private var thrown: Item? = null
    private var downX = 0f
    private var downY = 0f
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val throwDistance = Utils.dpToPx(context, THROW_DP).toFloat()
    private val throwVelocity = Utils.dpToPx(context, THROW_VELOCITY_DP).toFloat()
    private val fadeDistance = Utils.dpToPx(context, FADE_DP).toFloat()

    /**
     * The pointer left an icon. A click also makes it leave for a moment (it comes back on release), and
     * going from one icon to the next crosses the gap between them: it only counts as gone after a
     * moment, so neither the name nor the × blink.
     */
    private val pointerLeft = Runnable {
        hovered = null
        if (!labelLocked) showSelectedName()
        updateCloseButtons()
    }

    val selectedTask: AppTask? get() = items.getOrNull(selected)?.task

    init {
        val size = Utils.dpToPx(context, ITEM_DP)
        val iconSize = Utils.dpToPx(context, ICON_DP)
        // The gap between icons as a divider, so it stays right when a closed window leaves the row
        row.dividerDrawable = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setSize(Utils.dpToPx(context, GAP_DP), size)
        }
        row.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        // A thrown icon may leave the row up or down (the card does not clip it, see the layout);
        // sideways the row still ends at its edges when it scrolls
        scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.clipBounds = Rect(0, -THROW_ROOM_PX, v.width, v.height + THROW_ROOM_PX)
        }
        tasks.forEach { task ->
            val item = makeItem(task, icon(task), iconSize)
            row.addView(item.view, LinearLayout.LayoutParams(size, size))
            items.add(item)
        }
        closeAllButton.isVisible = canClose && items.isNotEmpty()
        closeAllButton.setOnClickListener { closeAll() }
        if (items.isEmpty()) showEmpty()
    }

    /** No window open: just the message, in the middle of the card, which goes away on its own. */
    private fun showEmpty() {
        scroll.isVisible = false
        label.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = 0 }
        card.updatePadding(bottom = card.paddingTop)
        label.setText(R.string.task_switcher_empty)
        view.postDelayed({ host.dismiss(this) }, EMPTY_CLOSE_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeItem(task: AppTask, drawable: Drawable, iconSize: Int): Item {
        val button = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.task_switcher_item_bg)
            contentDescription = task.name
        }
        button.addView(
            ImageView(context).apply { setImageDrawable(drawable) },
            FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        )
        // The × is drawn over the icon's corner but the icon's button handles it (by where the
        // pointer is), so going over it does not take the mouse off the icon. Its red under the
        // mouse is the activated state: marked hovered, the × would take the mouse and blink
        val closeButton = if (!canClose) null else ImageView(context).apply {
            setBackgroundResource(R.drawable.task_switcher_close_bg)
            setImageResource(R.drawable.ic_eg_close)
            val padding = Utils.dpToPx(context, CLOSE_PADDING_DP)
            setPadding(padding, padding, padding, padding)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        if (closeButton != null) {
            val closeSize = Utils.dpToPx(context, CLOSE_DP)
            val margin = Utils.dpToPx(context, CLOSE_MARGIN_DP)
            button.addView(
                closeButton,
                FrameLayout.LayoutParams(closeSize, closeSize, Gravity.TOP or Gravity.END).apply {
                    topMargin = margin
                    marginEnd = margin
                }
            )
        }
        val item = Item(task, button, closeButton)
        button.setOnClickListener { host.switchTo(task) }
        if (closeButton != null)
            ViewCompat.addAccessibilityAction(button, context.getString(R.string.taskbar_close_window)) { _, _ ->
                closeWindow(item)
                true
            }
        // With the mouse, the name below follows the window under the pointer, which also shows its ×;
        // off the icons it goes back to the selected one
        button.setOnHoverListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    view.removeCallbacks(pointerLeft)
                    if (hovered !== item) {
                        hovered = item
                        if (!labelLocked) label.text = task.name
                        updateCloseButtons()
                    }
                    closeButton?.isActivated = closeButton != null && onClose(closeButton, event)
                }

                MotionEvent.ACTION_HOVER_EXIT -> {
                    closeButton?.isActivated = false
                    view.removeCallbacks(pointerLeft)
                    view.postDelayed(pointerLeft, LEAVE_DELAY_MS)
                }
            }
            false
        }
        // A click makes the pointer leave the icon while pressed and come back on release: the name
        // stays on the pressed window, which the switcher then opens or closes
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.removeCallbacks(pointerLeft)
                    labelLocked = true
                    label.text = task.name
                    if (closeButton != null && onClose(closeButton, event)) {
                        // The press is the ×'s, not the icon's
                        pressedClose = item
                        closeButton.isPressed = true
                        return@setOnTouchListener true
                    }
                    if (canClose) startTracking(event)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (pressedClose === item) {
                        closeButton?.isPressed = closeButton != null && onClose(closeButton, event)
                        return@setOnTouchListener true
                    }
                    if (canClose && drag(item, event)) return@setOnTouchListener true
                }

                MotionEvent.ACTION_UP -> {
                    if (pressedClose === item) {
                        pressedClose = null
                        val onIt = closeButton?.isPressed == true
                        closeButton?.isPressed = false
                        if (onIt) closeWindow(item) else releaseLabel()
                        return@setOnTouchListener true
                    }
                    if (thrown === item) {
                        letGo(item, event)
                        return@setOnTouchListener true
                    }
                    stopTracking()
                    // Let go off the icon: nothing opens
                    if (event.x < 0 || event.y < 0 || event.x > button.width || event.y > button.height)
                        releaseLabel()
                }

                MotionEvent.ACTION_CANCEL -> {
                    pressedClose = null
                    closeButton?.isPressed = false
                    if (thrown === item) {
                        thrown = null
                        backInPlace(item)
                    }
                    stopTracking()
                    releaseLabel()
                }
            }
            false
        }
        return item
    }

    /** Whether [event] (on the icon's button) falls on its ×. */
    private fun onClose(closeButton: View, event: MotionEvent): Boolean {
        if (!closeButton.isVisible) return false
        return event.x >= closeButton.left && event.x <= closeButton.right &&
                event.y >= closeButton.top && event.y <= closeButton.bottom
    }

    // ---- Throwing an icon out of the row (a finger's swipe up or down, like scrolling) closes it

    private fun startTracking(event: MotionEvent) {
        downX = event.rawX
        downY = event.rawY
        velocity?.recycle()
        velocity = VelocityTracker.obtain()
        track(event)
    }

    /** On screen coordinates: the icon moves along with the finger, so its own would not change. */
    private fun track(event: MotionEvent) {
        val tracker = velocity ?: return
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    private fun stopTracking() {
        velocity?.recycle()
        velocity = null
    }

    /**
     * Follows a press on an icon: once it goes up or down rather than sideways (sideways scrolls the
     * row), the icon follows the finger and fades. True while it is being thrown.
     */
    private fun drag(item: Item, event: MotionEvent): Boolean {
        track(event)
        val dy = event.rawY - downY
        if (thrown !== item) {
            if (abs(dy) <= touchSlop || abs(dy) <= abs(event.rawX - downX)) return false
            thrown = item
            item.view.parent.requestDisallowInterceptTouchEvent(true)
            // The press no longer opens the window
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            item.view.onTouchEvent(cancel)
            cancel.recycle()
        }
        val offset = dy - sign(dy) * touchSlop
        item.view.translationY = offset
        item.view.alpha = (1f - abs(offset) / fadeDistance).coerceAtLeast(MIN_ALPHA)
        return true
    }

    /** The thrown icon was let go: far or fast enough its window closes, otherwise it goes back. */
    private fun letGo(item: Item, event: MotionEvent) {
        track(event)
        thrown = null
        val tracker = velocity
        tracker?.computeCurrentVelocity(1000)
        val speed = tracker?.yVelocity ?: 0f
        stopTracking()
        val offset = item.view.translationY
        val away = abs(offset) >= throwDistance || (abs(speed) >= throwVelocity && sign(speed) == sign(offset))
        if (away) closeWindow(item, sign(offset))
        else {
            backInPlace(item)
            releaseLabel()
        }
    }

    private fun backInPlace(item: Item) {
        item.view.animate().translationY(0f).alpha(1f).setDuration(BACK_ANIM_MS).start()
    }

    /** A press ended without opening nor closing anything: the name follows the pointer again. */
    private fun releaseLabel() {
        labelLocked = false
        val over = hovered
        if (over != null) label.text = over.task.name else showSelectedName()
    }

    /** The × only shows on the window under the mouse; a finger throws the icon instead. */
    private fun updateCloseButtons() {
        items.forEach { it.closeButton?.visibility = if (it === hovered) View.VISIBLE else View.INVISIBLE }
    }

    /**
     * Closes [item]'s window and takes it out of the row; the next one takes its place. A thrown icon
     * keeps going the way it was thrown ([direction] up -1, down 1); otherwise it shrinks away.
     */
    private fun closeWindow(item: Item, direction: Float = 0f) {
        labelLocked = false
        val index = items.indexOf(item)
        if (index < 0) return
        if (!host.close(item.task)) {
            backInPlace(item)
            releaseLabel()
            return
        }
        items.removeAt(index)
        if (hovered === item) hovered = null
        item.view.isEnabled = false
        val out = item.view.animate().alpha(0f).setDuration(CLOSE_ANIM_MS)
        if (direction != 0f) out.translationY(item.view.translationY + direction * fadeDistance)
        else out.scaleX(CLOSE_SCALE).scaleY(CLOSE_SCALE)
        out.withEndAction {
            row.removeView(item.view)
            scrollToSelected()
        }.start()
        if (items.isEmpty()) {
            host.dismiss(this)
            return
        }
        if (index < selected || selected == items.size) selected--
        select(selected)
    }

    /** "Close all": every window at once, and the switcher with them. */
    private fun closeAll() {
        items.toList().forEach { host.close(it.task) }
        host.dismiss(this)
    }

    /** Supr: closes the selected window, as in Windows' Alt+Tab. */
    fun closeSelected() {
        if (canClose) items.getOrNull(selected)?.let { closeWindow(it) }
    }

    /** Selects the window at [index], going round at both ends, and shows its name. */
    fun select(index: Int) {
        if (items.isEmpty()) return
        selected = Math.floorMod(index, items.size)
        items.forEachIndexed { i, item -> item.view.isSelected = i == selected }
        showSelectedName()
        updateCloseButtons()
        scrollToSelected()
    }

    fun move(step: Int) = select(selected + step)

    /** With more windows than fit on screen, keeps the selected one in the middle of the row. */
    private fun scrollToSelected() {
        val item = items.getOrNull(selected)?.view ?: return
        scroll.post { scroll.smoothScrollTo(item.left - (scroll.width - item.width) / 2, 0) }
    }

    private fun showSelectedName() {
        items.getOrNull(selected)?.let { label.text = it.task.name }
    }

    private companion object {
        const val ITEM_DP = 76
        const val ICON_DP = 46
        const val GAP_DP = 8
        const val CLOSE_DP = 22
        const val CLOSE_PADDING_DP = 5
        const val CLOSE_MARGIN_DP = 3
        const val LEAVE_DELAY_MS = 150L
        const val CLOSE_ANIM_MS = 140L
        const val CLOSE_SCALE = 0.8f

        /** With no window, how long the message stays. */
        const val EMPTY_CLOSE_MS = 3000L

        /** A thrown icon closes its window past this distance, or let go this fast (per second). */
        const val THROW_DP = 32
        const val THROW_VELOCITY_DP = 800

        /** Over this distance a thrown icon fades out (down to MIN_ALPHA while still held). */
        const val FADE_DP = 48
        const val MIN_ALPHA = 0.15f
        const val BACK_ANIM_MS = 160L

        /** Room above and below the row for a thrown icon (the window's own edge clips it anyway). */
        const val THROW_ROOM_PX = 4096
    }
}
