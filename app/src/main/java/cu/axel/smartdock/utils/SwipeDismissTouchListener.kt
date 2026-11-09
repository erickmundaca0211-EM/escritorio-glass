package cu.axel.smartdock.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sign

/**
 * Lets the user swipe a notification sideways to close it, like the system shade.
 *
 * Taps and long presses still reach the view: the listener only takes over once the finger clearly
 * moves sideways. When [canDismiss] is false the view follows the finger with resistance and springs
 * back. With [collapse] the view also folds its height away before [onDismiss] runs, so the list
 * around it closes the gap smoothly.
 */
class SwipeDismissTouchListener(
    private val canDismiss: Boolean,
    private val collapse: Boolean,
    private val onSwipeStart: () -> Unit = {},
    private val onSpringBack: () -> Unit = {},
    private val onDismiss: (View) -> Unit
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var armed = false
    private var tracker: VelocityTracker? = null

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val config = ViewConfiguration.get(view.context)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                swiping = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { track(it, event) }
            }

            MotionEvent.ACTION_MOVE -> {
                val tracker = tracker ?: return false
                track(tracker, event)
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!swiping && abs(dx) > config.scaledTouchSlop && abs(dx) > abs(dy) * 1.5f) {
                    swiping = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    // Cancel the press so lifting the finger is not taken as a tap
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    view.onTouchEvent(cancel)
                    cancel.recycle()
                    onSwipeStart()
                }
                if (swiping) {
                    val shift = if (canDismiss) dx else dx * RESISTANCE
                    view.translationX = shift
                    view.alpha = (1f - abs(shift) / view.width.coerceAtLeast(1)).coerceIn(MIN_ALPHA, 1f)
                    // A short tick when letting go would close it, and when it no longer would
                    val past = canDismiss && abs(dx) > view.width * DISMISS_FRACTION
                    if (past != armed) {
                        armed = past
                        Haptics.threshold(view, past)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                val tracker = tracker ?: return false
                track(tracker, event)
                val wasSwiping = swiping
                if (swiping) {
                    tracker.computeCurrentVelocity(1000)
                    val velocity = tracker.xVelocity
                    val dx = event.rawX - downX
                    val flung = abs(velocity) > config.scaledMinimumFlingVelocity * FLING_FACTOR &&
                            sign(velocity) == sign(dx)
                    if (canDismiss && (abs(dx) > view.width * DISMISS_FRACTION || flung))
                        dismiss(view, if (dx > 0) 1f else -1f)
                    else
                        springBack(view)
                }
                release()
                return wasSwiping
            }

            MotionEvent.ACTION_CANCEL -> {
                if (swiping) springBack(view)
                release()
            }
        }
        return false
    }

    /** Velocity is measured on screen coordinates: the view itself moves under the finger. */
    private fun track(tracker: VelocityTracker, event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    private fun release() {
        swiping = false
        armed = false
        tracker?.recycle()
        tracker = null
    }

    private fun springBack(view: View) {
        view.animate().translationX(0f).alpha(1f).setDuration(ANIMATION_MS).setListener(null)
        onSpringBack()
    }

    private fun dismiss(view: View, direction: Float) {
        view.animate().translationX(direction * view.width).alpha(0f).setDuration(ANIMATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    if (collapse) collapse(view) else onDismiss(view)
                }
            })
    }

    private fun collapse(view: View) {
        val params = view.layoutParams
        val height = view.height
        ValueAnimator.ofInt(height, 0).apply {
            duration = ANIMATION_MS
            addUpdateListener {
                params.height = it.animatedValue as Int
                view.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDismiss(view)
                }
            })
            start()
        }
    }

    private companion object {
        /** Share of the width the finger must travel for a slow swipe to close the notification. */
        const val DISMISS_FRACTION = 0.4f

        /** How much faster than a minimal fling a quick flick must be to close it. */
        const val FLING_FACTOR = 4

        /** How far a notification that cannot be closed follows the finger. */
        const val RESISTANCE = 0.25f

        const val MIN_ALPHA = 0.15f
        const val ANIMATION_MS = 180L
    }
}
