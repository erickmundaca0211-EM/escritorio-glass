package cu.axel.smartdock.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * Root of the taskbar window. It tells [onBarHover] when the mouse enters or leaves the whole bar
 * (to show the auto-hidden bar), while every button inside still gets its own hover (the Windows 11
 * highlight). Upstream intercepted all hover events here, so no button could ever look hovered.
 */
class HoverInterceptorLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    /** Called with MotionEvent.ACTION_HOVER_ENTER or ACTION_HOVER_EXIT for the window as a whole. */
    var onBarHover: ((Int) -> Unit)? = null
    private var inside = false

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchHoverEvent(event)
        // Here the events are the window's own: entering, moving over and leaving the bar
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> if (!inside) {
                inside = true
                onBarHover?.invoke(MotionEvent.ACTION_HOVER_ENTER)
            }

            MotionEvent.ACTION_HOVER_EXIT -> if (inside) {
                inside = false
                onBarHover?.invoke(MotionEvent.ACTION_HOVER_EXIT)
            }
        }
        return handled
    }
}
