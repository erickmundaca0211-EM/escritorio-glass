package cu.axel.smartdock.adapters

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * Taskbar icons coming and going like Windows 11: a new app's icon rises and grows into place with a
 * small bounce, a closed one shrinks and fades away, and the others slide to their new places.
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
        view.animate().alpha(0f).scaleX(SMALL).scaleY(SMALL).setDuration(REMOVE_MS)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
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
        view.translationY = view.resources.displayMetrics.density * RISE_DP
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
        view.translationX = -dx.toFloat()
        view.translationY = -dy.toFloat()
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
        // Change animations are off: just say it is done
        dispatchChangeFinished(oldHolder, true)
        if (newHolder != null && newHolder !== oldHolder) dispatchChangeFinished(newHolder, false)
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

    private fun reset(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    companion object {
        const val ADD_MS = 260L
        const val REMOVE_MS = 160L
        const val MOVE_MS = 220L
        private const val SMALL = 0.5f
        private const val RISE_DP = 10f
        private const val BOUNCE = 1.6f
    }
}
