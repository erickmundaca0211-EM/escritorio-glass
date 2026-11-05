package cu.axel.smartdock.glass

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import java.lang.reflect.Method

/**
 * Glass level 2: real blur of whatever is behind the window, clipped to a rounded rect.
 * Uses the hidden `ViewRootImpl#createBackgroundBlurDrawable()` (validated in docs/SPIKES.md, spike a);
 * hidden-API access is opened by RestrictionBypass at process start. If anything fails it returns null
 * once and for all, and callers fall back to level 1.
 */
object GlassBlur {
    private const val TAG = "EG"

    @Volatile
    private var unavailable = false
    private var getViewRootImpl: Method? = null
    private var createBlur: Method? = null
    private var setBlurRadius: Method? = null
    private var setCornerRadius: Method? = null
    private var setColor: Method? = null

    /** Creates a blur drawable for [view]'s window; the view must be attached. */
    fun create(view: View, blurRadiusPx: Int, cornerRadiusPx: Float): Drawable? {
        if (unavailable || !view.isAttachedToWindow) return null
        return try {
            val getRoot = getViewRootImpl ?: View::class.java.getMethod("getViewRootImpl")
                .also { getViewRootImpl = it }
            val viewRoot = getRoot.invoke(view) ?: return null
            val create = createBlur ?: viewRoot.javaClass.getMethod("createBackgroundBlurDrawable")
                .also { createBlur = it }
            val blur = create.invoke(viewRoot) as Drawable
            val cls = blur.javaClass
            (setBlurRadius ?: cls.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
                .also { setBlurRadius = it }).invoke(blur, blurRadiusPx)
            (setCornerRadius ?: cls.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                .also { setCornerRadius = it }).invoke(blur, cornerRadiusPx)
            (setColor ?: cls.getMethod("setColor", Int::class.javaPrimitiveType)
                .also { setColor = it }).invoke(blur, Color.TRANSPARENT)
            blur
        } catch (e: Throwable) {
            unavailable = true
            Log.w(TAG, "Background blur unavailable, using tint only: ${e.cause ?: e}")
            null
        }
    }
}
