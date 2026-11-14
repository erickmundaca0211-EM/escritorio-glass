package cu.axel.smartdock.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import cu.axel.smartdock.R
import kotlin.math.roundToInt

/**
 * Opening and closing motion of the taskbar panels and menus: they rise a little while fading in,
 * and sink while fading out.
 *
 * The real blur behind the glass (level 2) ignores the view's own alpha, so the backgrounds of the
 * [glass] views follow the same fade; otherwise a blurred rectangle would pop in before the panel.
 */
object PanelAnimator {
    private const val SHOW_MS = 220L
    private const val HIDE_MS = 140L
    private const val RISE_DP = 14

    /** Longest wait for a new window's first frame before the opening motion starts anyway. */
    private const val FIRST_FRAME_WAIT_MS = 120L

    /** Fast start and a long, soft landing. */
    private val showCurve = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** Slow start, quick exit. */
    private val hideCurve = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /**
     * Opens [view] once its window has drawn the first frame. A panel added in a new window spends
     * that frame building everything (36 ms for Start on the POCO, plus the wait for the surface):
     * started right away, the motion lost its first quarter there and the panel popped in. Until it
     * starts the panel is fully transparent; it starts [FIRST_FRAME_WAIT_MS] later at the latest.
     */
    fun show(view: View, glass: List<View> = listOf(view)) {
        val animator = animate(view, glass, 0f, 1f, SHOW_MS, showCurve, null, start = false)
        var started = false
        val start = Runnable {
            // A hide() or another show() since then replaced this motion
            if (started || view.getTag(R.id.panel_animator) !== animator) return@Runnable
            started = true
            animator.start()
        }
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                // After this frame: the first step of the motion is the next one
                view.post(start)
                return true
            }
        })
        view.postDelayed(start, FIRST_FRAME_WAIT_MS)
    }

    /**
     * Runs [onEnd] once the panel is invisible; it is skipped if [show] starts again before that.
     * [onEnd] should take the window away with `removeViewImmediate`: plain `removeView` drops the
     * window's renderer first and lets one more frame be drawn in software, which ignores the fade
     * and the slide of the window's root view, so the whole panel flashed back for a moment.
     */
    fun hide(view: View, glass: List<View> = listOf(view), onEnd: () -> Unit) =
        animate(view, glass, view.alpha, 0f, HIDE_MS, hideCurve, onEnd)

    /** Stops any motion of [view] (for example before removing its window right away). */
    fun cancel(view: View) {
        (view.getTag(R.id.panel_animator) as? Animator)?.cancel()
    }

    private fun animate(
        view: View, glass: List<View>, from: Float, to: Float, duration: Long,
        curve: PathInterpolator, onEnd: (() -> Unit)?, start: Boolean = true
    ): Animator {
        cancel(view)
        val rise = Utils.dpToPx(view.context, RISE_DP).toFloat()
        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.interpolator = curve
        animator.addUpdateListener { apply(view, glass, it.animatedValue as Float, rise) }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (view.getTag(R.id.panel_animator) === animation)
                    view.setTag(R.id.panel_animator, null)
                if (!cancelled) onEnd?.invoke()
            }
        })
        view.setTag(R.id.panel_animator, animator)
        apply(view, glass, from, rise)
        if (start) animator.start()
        return animator
    }

    private fun apply(view: View, glass: List<View>, progress: Float, rise: Float) {
        view.alpha = progress
        view.translationY = (1f - progress) * rise
        val alpha = (progress * 255).roundToInt().coerceIn(0, 255)
        glass.forEach { it.background?.alpha = alpha }
    }
}
