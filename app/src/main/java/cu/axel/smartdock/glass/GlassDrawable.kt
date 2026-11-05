package cu.axel.smartdock.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Glass level 1 (docs/DISENO.md): tint, 1 dp border, a brighter 1 dp line along the top edge and a soft
 * specular glow from the top-left corner. Works everywhere; level 2 puts a real blur underneath it.
 */
class GlassDrawable(
    private val cornerRadius: Float,
    tint: Int,
    private val density: Float,
    border: Int = BORDER,
    private val highlight: Int = HIGHLIGHT,
    private val specular: Int = SPECULAR
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = border
    }
    private val rect = RectF()
    private val clip = Path()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
        clip.reset()
        clip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        val glowRadius = max(bounds.width(), bounds.height()) * 0.55f
        specularPaint.shader = if (glowRadius > 0f) RadialGradient(
            rect.left, rect.top, glowRadius, specular, Color.TRANSPARENT, Shader.TileMode.CLAMP
        ) else null
        highlightPaint.shader = LinearGradient(
            rect.left, 0f, rect.right, 0f,
            intArrayOf(Color.TRANSPARENT, highlight, highlight, Color.TRANSPARENT),
            floatArrayOf(0f, 0.18f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, specularPaint)
        val y = rect.top + density * 1.5f
        canvas.drawLine(rect.left, y, rect.right, y, highlightPaint)
        canvas.restore()
        val inset = density / 2f
        val r = (cornerRadius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, r, r, borderPaint
        )
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, cornerRadius)
    }

    override fun setAlpha(alpha: Int) {
        // Opacity is controlled by the tint (GlassSettings), not by View/Drawable alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val BORDER = 0x2EFFFFFF        // white 18 %
        const val HIGHLIGHT = 0x5CFFFFFF     // white 36 %
        const val SPECULAR = 0x29FFFFFF      // white 16 %
        const val TINT = 0xFF121824.toInt()  // glass_tint without alpha
    }
}
