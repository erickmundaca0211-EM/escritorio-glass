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
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.tan

/**
 * Window switcher, like Alt+Tab in Windows 11 but with icons instead of thumbnails: the open windows
 * in a row, most recently used first, and the name of the selected one below. The keyboard moves the
 * selection (DockService picks it when Alt is let go); a click on an icon picks that window at once.
 * When windows can be closed (Shizuku): an icon thrown up or down out of the row, straight or on a
 * diagonal, closes its window, like swiping away a recent app (with the mouse, windows are closed from
 * the taskbar); Supr closes the selected one and "Close all" closes them all at once.
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

    /** A window in the row and its button. */
    private class Item(val task: AppTask, val view: FrameLayout)

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

    /** The icon being thrown, where its press started on screen and how fast it moves. */
    private var thrown: Item? = null
    private var downX = 0f
    private var downY = 0f
    private var velocity: VelocityTracker? = null

    /** The press went sideways: it scrolls the row instead of throwing the icon. */
    private var scrolling = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val throwDistance = Utils.dpToPx(context, THROW_DP).toFloat()
    private val throwVelocity = Utils.dpToPx(context, THROW_VELOCITY_DP).toFloat()
    private val fadeDistance = Utils.dpToPx(context, FADE_DP).toFloat()

    /** How far sideways a throw may go for each step up or down (the cone's half-angle, THROW_CONE_DEG). */
    private val coneSlope = tan(Math.toRadians(THROW_CONE_DEG)).toFloat()

    /**
     * The pointer left an icon. A click also makes it leave for a moment (it comes back on release), and
     * going from one icon to the next crosses the gap between them: it only counts as gone after a
     * moment, so the name does not blink.
     */
    private val pointerLeft = Runnable {
        hovered = null
        if (!labelLocked) showSelectedName()
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
        // A thrown icon may leave the row up, down or on a diagonal (the card does not clip it, see
        // the layout); only a row that scrolls still ends at its edges sideways
        scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.clipBounds = if (row.width > v.width) Rect(0, -THROW_ROOM_PX, v.width, v.height + THROW_ROOM_PX)
            else null
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
        val item = Item(task, button)
        // Raised over its neighbours while thrown on a diagonal, without a shadow
        button.outlineProvider = null
        button.setOnClickListener { host.switchTo(task) }
        // No × on the icons: with the mouse, windows are closed from the taskbar
        if (canClose)
            ViewCompat.addAccessibilityAction(button, context.getString(R.string.taskbar_close_window)) { _, _ ->
                closeWindow(item)
                true
            }
        // With the mouse, the name below follows the window under the pointer; off the icons it goes
        // back to the selected one
        button.setOnHoverListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    view.removeCallbacks(pointerLeft)
                    if (hovered !== item) {
                        hovered = item
                        if (!labelLocked) label.text = task.name
                    }
                }

                MotionEvent.ACTION_HOVER_EXIT -> {
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
                    if (canClose) {
                        startTracking(event)
                        // The row waits to scroll until the press shows its way (drag)
                        button.parent.requestDisallowInterceptTouchEvent(true)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (canClose && drag(item, event)) return@setOnTouchListener true
                }

                MotionEvent.ACTION_UP -> {
                    if (thrown === item) {
                        letGo(item, event)
                        return@setOnTouchListener true
                    }
                    stopTracking()
                    // Let go off the icon, or after going sideways: nothing opens
                    if (scrolling || event.x < 0 || event.y < 0 || event.x > button.width || event.y > button.height)
                        releaseLabel()
                }

                MotionEvent.ACTION_CANCEL -> {
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

    // ---- Throwing an icon out of the row (a finger's swipe up or down, like scrolling, straight or
    // on a diagonal: anywhere in a cone of THROW_CONE_DEG either side) closes it

    private fun startTracking(event: MotionEvent) {
        downX = event.rawX
        downY = event.rawY
        scrolling = false
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

    /** Whether a move by ([dx], [dy]) goes up or down within the throw cone, not sideways. */
    private fun inCone(dx: Float, dy: Float) = dy != 0f && abs(dx) <= abs(dy) * coneSlope

    /**
     * Follows a press on an icon: once it goes up or down, straight or on a diagonal, the icon follows
     * the finger and fades; flatter, the press is handed to the row, which scrolls. True while it is
     * being thrown.
     */
    private fun drag(item: Item, event: MotionEvent): Boolean {
        if (scrolling) return false
        track(event)
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        val distance = hypot(dx, dy)
        if (thrown !== item) {
            if (distance <= touchSlop) return false
            // Either way the press no longer opens the window
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            item.view.onTouchEvent(cancel)
            cancel.recycle()
            if (!inCone(dx, dy)) {
                // Sideways: the row takes the press over on the next move
                scrolling = true
                item.view.parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
            thrown = item
            item.view.translationZ = THROWN_Z
        }
        // The icon follows the finger from where the press became a throw
        val follow = ((distance - touchSlop) / distance).coerceAtLeast(0f)
        item.view.translationX = dx * follow
        item.view.translationY = dy * follow
        item.view.alpha = (1f - distance * follow / fadeDistance).coerceAtLeast(MIN_ALPHA)
        return true
    }

    /**
     * The thrown icon was let go: far enough, or fast enough, up or down within the cone, its window
     * closes; otherwise it goes back.
     */
    private fun letGo(item: Item, event: MotionEvent) {
        track(event)
        thrown = null
        val tracker = velocity
        tracker?.computeCurrentVelocity(1000)
        val speedX = tracker?.xVelocity ?: 0f
        val speedY = tracker?.yVelocity ?: 0f
        stopTracking()
        val x = item.view.translationX
        val y = item.view.translationY
        val far = hypot(x, y) >= throwDistance && inCone(x, y)
        val flung = hypot(speedX, speedY) >= throwVelocity && inCone(speedX, speedY) && sign(speedY) == sign(y)
        if (far || flung) closeWindow(item, x, y)
        else {
            backInPlace(item)
            releaseLabel()
        }
    }

    private fun backInPlace(item: Item) {
        item.view.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(BACK_ANIM_MS)
            .withEndAction { item.view.translationZ = 0f }.start()
    }

    /** A press ended without opening nor closing anything: the name follows the pointer again. */
    private fun releaseLabel() {
        labelLocked = false
        val over = hovered
        if (over != null) label.text = over.task.name else showSelectedName()
    }

    /**
     * Closes [item]'s window and takes it out of the row; the next one takes its place. A thrown icon
     * keeps going the way it was thrown (along [throwX], [throwY]); otherwise it shrinks away.
     */
    private fun closeWindow(item: Item, throwX: Float = 0f, throwY: Float = 0f) {
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
        val thrownBy = hypot(throwX, throwY)
        if (thrownBy > 0f) out.translationX(item.view.translationX + throwX / thrownBy * fadeDistance)
            .translationY(item.view.translationY + throwY / thrownBy * fadeDistance)
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
        const val LEAVE_DELAY_MS = 150L
        const val CLOSE_ANIM_MS = 140L
        const val CLOSE_SCALE = 0.8f

        /** With no window, how long the message stays. */
        const val EMPTY_CLOSE_MS = 3000L

        /** A thrown icon closes its window past this distance, or let go this fast (per second). */
        const val THROW_DP = 32
        const val THROW_VELOCITY_DP = 800

        /**
         * How far from straight up or down (degrees, either side) a press still throws the icon: a
         * fan of diagonals. Flatter than that, it scrolls the row.
         */
        const val THROW_CONE_DEG = 60.0

        /** A thrown icon is drawn over its neighbours (it can cross them on a diagonal). */
        const val THROWN_Z = 1f

        /** Over this distance a thrown icon fades out (down to MIN_ALPHA while still held). */
        const val FADE_DP = 48
        const val MIN_ALPHA = 0.15f
        const val BACK_ANIM_MS = 160L

        /** Room above and below the row for a thrown icon (the window's own edge clips it anyway). */
        const val THROW_ROOM_PX = 4096
    }
}
