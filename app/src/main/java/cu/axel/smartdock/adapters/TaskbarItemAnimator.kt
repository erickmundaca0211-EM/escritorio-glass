package cu.axel.smartdock.adapters

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * Taskbar icons coming and going like Windows 11: a new app's icon rises from below the bar into place
 * with a small bounce, a closed one sinks back down into the bar as it fades, and the others slide to
 * their new places. The list clips its items, so the icon seems to go in and out of the bar's edge.
 */
class TaskbarItemAnimator : SimpleItemAnimator() {
    private val running = HashSet<RecyclerView.ViewHolder>()

    init {
        // A changed icon (active app, number of windows) is redrawn in place, without a cross-fade
        supportsChangeAnimations = false
        addDuration = ADD_MS
        removeDuration = REMOVE_MS
        moveDuration = MOVE_MS
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        running.add(holder)
        // Sinks down into the bar while it fades, the way it rose when the app opened
        view.animate().alpha(0f).scaleX(SMALL).scaleY(SMALL).translationY(drop(view))
            .setDuration(REMOVE_MS).setStartDelay(0).setInterpolator(AccelerateInterpolator(SINK))
            .withEndAction {
                reset(view)
                running.remove(holder)
                dispatchRemoveFinished(holder)
                finishIfIdle()
            }.start()
        return false
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        view.alpha = 0f
        view.scaleX = SMALL
        view.scaleY = SMALL
        view.translationY = drop(view)
        running.add(holder)
        // Starts once the closing icons are gone and the others have begun to move
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(ADD_MS)
            .setStartDelay(REMOVE_MS / 2).setInterpolator(OvershootInterpolator(BOUNCE))
            .withEndAction {
                reset(view)
                running.remove(holder)
                dispatchAddFinished(holder)
                finishIfIdle()
            }.start()
        return false
    }

    override fun animateMove(
        holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int
    ): Boolean {
        val view = holder.itemView
        val dx = fromX + view.translationX.toInt() - toX
        val dy = fromY + view.translationY.toInt() - toY
        if (dx == 0 && dy == 0) {
            dispatchMoveFinished(holder)
            return false
        }
        // Already laid out at its new place: start it where it was drawn before, then glide to zero
        view.translationX = dx.toFloat()
        view.translationY = dy.toFloat()
        running.add(holder)
        view.animate().translationX(0f).translationY(0f).setDuration(MOVE_MS)
            .setStartDelay(0).setInterpolator(DecelerateInterpolator(1.5f)).withEndAction {
                running.remove(holder)
                dispatchMoveFinished(holder)
                finishIfIdle()
            }.start()
        return false
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder, newHolder: RecyclerView.ViewHolder?,
        fromLeft: Int, fromTop: Int, toLeft: Int, toTop: Int
    ): Boolean {
        // The same icon redrawn (it became active or not, the focus moves when a window closes) can
        // also change place: it must slide there like the others, or it jumps
        if (newHolder == null || newHolder === oldHolder)
            return animateMove(oldHolder, fromLeft, fromTop, toLeft, toTop)
        dispatchChangeFinished(oldHolder, true)
        dispatchChangeFinished(newHolder, false)
        return false
    }

    override fun runPendingAnimations() {
        // Every animation above starts as soon as it is requested
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        item.itemView.animate().cancel()
        reset(item.itemView)
        if (running.remove(item)) dispatchAnimationFinished(item)
        finishIfIdle()
    }

    override fun endAnimations() {
        for (holder in running.toList()) {
            holder.itemView.animate().cancel()
            reset(holder.itemView)
            dispatchAnimationFinished(holder)
        }
        running.clear()
        dispatchAnimationsFinished()
    }

    override fun isRunning() = running.isNotEmpty()

    private fun finishIfIdle() {
        if (running.isEmpty()) dispatchAnimationsFinished()
    }

    /** How far below its place an icon starts when it opens and ends when it closes. */
    private fun drop(view: View) = view.resources.displayMetrics.density * DROP_DP

    private fun reset(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    companion object {
        const val ADD_MS = 260L
        const val REMOVE_MS = 220L
        const val MOVE_MS = 220L
        private const val SMALL = 0.8f
        private const val DROP_DP = 24f
        private const val BOUNCE = 1.6f
        private const val SINK = 1.5f
    }
}
