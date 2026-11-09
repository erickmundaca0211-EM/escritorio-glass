package cu.axel.smartdock.glass

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Taskbar status icons drawn in code so they can change smoothly (docs/DISENO.md, "Barra de tareas"):
 * Wi-Fi and mobile signal by strength, volume by level and a horizontal battery with its percentage.
 * Own line style, white on glass; nothing taken from other systems' icon sets.
 */
abstract class AnimatedLevelDrawable(protected val density: Float, color: Int) : Drawable() {
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    /** Level being drawn; moves towards the target level when it changes. */
    protected var shownLevel = 0f
    private var animator: ValueAnimator? = null

    protected fun animateTo(target: Float) {
        if (target == shownLevel && animator?.isRunning != true) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shownLevel, target).apply {
            duration = LEVEL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownLevel = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    protected fun dp(value: Float) = value * density

    /** Draws with [paint] at a fraction of its alpha (0 to 1). */
    protected inline fun withAlpha(paint: Paint, fraction: Float, block: () -> Unit) {
        val old = paint.alpha
        paint.alpha = (old * fraction.coerceIn(0f, 1f)).roundToInt()
        block()
        paint.alpha = old
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val LEVEL_MS = 380L

        /** Alpha of the parts that are "off" (weak signal, empty bars). */
        const val DIM = 0.28f
    }
}

/** Wi-Fi fan: a dot and three arcs that light up with the signal (0 to 4). */
class WifiDrawable(density: Float, color: Int) : AnimatedLevelDrawable(density, color) {
    enum class State { CONNECTED, NO_INTERNET, OFF }

    private var state = State.OFF
    private val arc = RectF()

    fun set(state: State, level: Int) {
        val changed = state != this.state
        this.state = state
        // Connected always shows at least the dot
        animateTo(if (state == State.OFF) 0f else max(1, level.coerceIn(0, 4)).toFloat())
        if (changed) invalidateSelf()
    }

    override fun getIntrinsicWidth() = dp(18f).roundToInt()
    override fun getIntrinsicHeight() = dp(18f).roundToInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val scale = min(b.width(), b.height()) / dp(18f)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(scale, scale)
        stroke.strokeWidth = dp(1.8f)
        val cx = dp(9f)
        val cy = dp(14.5f)
        val whole = if (state == State.NO_INTERNET) 0.55f else 1f
        // Element 0 is the dot, 1..3 the arcs from small to big
        for (i in 0..3) {
            val on = (shownLevel - i).coerceIn(0f, 1f)
            val alpha = (DIM + (1f - DIM) * on) * whole
            if (i == 0) withAlpha(fill, alpha) { canvas.drawCircle(cx, cy, dp(1.5f), fill) }
            else {
                val r = dp(3.6f * i + 0.6f)
                arc.set(cx - r, cy - r, cx + r, cy + r)
                withAlpha(stroke, alpha) { canvas.drawArc(arc, 225f, 90f, false, stroke) }
            }
        }
        if (state == State.OFF) {
            stroke.strokeWidth = dp(1.6f)
            canvas.drawLine(dp(3f), dp(3f), dp(15f), dp(15f), stroke)
        }
        canvas.restore()
    }
}

/** Mobile signal: four rising bars that fill with the signal (0 to 4). */
class CellDrawable(density: Float, color: Int) : AnimatedLevelDrawable(density, color) {
    private var noService = false
    private val bar = RectF()

    fun set(level: Int, noService: Boolean) {
        val changed = noService != this.noService
        this.noService = noService
        animateTo(if (noService) 0f else level.coerceIn(0, 4).toFloat())
        if (changed) invalidateSelf()
    }

    override fun getIntrinsicWidth() = dp(18f).roundToInt()
    override fun getIntrinsicHeight() = dp(18f).roundToInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val scale = min(b.width(), b.height()) / dp(18f)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(scale, scale)
        val width = dp(2.6f)
        val gap = dp(1.5f)
        val left0 = dp(9f) - (4 * width + 3 * gap) / 2f
        val bottom = dp(15f)
        val full = dp(12f)
        for (i in 0..3) {
            val on = (shownLevel - i).coerceIn(0f, 1f)
            val left = left0 + i * (width + gap)
            bar.set(left, bottom - full * (i + 1) / 4f, left + width, bottom)
            withAlpha(fill, DIM + (1f - DIM) * on) {
                canvas.drawRoundRect(bar, dp(0.8f), dp(0.8f), fill)
            }
        }
        if (noService) {
            stroke.strokeWidth = dp(1.6f)
            canvas.drawLine(dp(3f), dp(3f), dp(15f), dp(15f), stroke)
        }
        canvas.restore()
    }
}

/** Speaker with 0 to 3 waves by volume; crossed out when muted. */
class VolumeDrawable(density: Float, color: Int) : AnimatedLevelDrawable(density, color) {
    private var muted = false
    private val speaker = Path()
    private val arc = RectF()

    fun set(waves: Int, muted: Boolean) {
        val changed = muted != this.muted
        this.muted = muted
        animateTo(if (muted) 0f else waves.coerceIn(0, 3).toFloat())
        if (changed) invalidateSelf()
    }

    override fun getIntrinsicWidth() = dp(18f).roundToInt()
    override fun getIntrinsicHeight() = dp(18f).roundToInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val scale = min(b.width(), b.height()) / dp(18f)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(scale, scale)
        stroke.strokeWidth = dp(1.6f)
        speaker.reset()
        speaker.moveTo(dp(2f), dp(6.8f))
        speaker.lineTo(dp(4.8f), dp(6.8f))
        speaker.lineTo(dp(8.6f), dp(3.6f))
        speaker.lineTo(dp(8.6f), dp(14.4f))
        speaker.lineTo(dp(4.8f), dp(11.2f))
        speaker.lineTo(dp(2f), dp(11.2f))
        speaker.close()
        canvas.drawPath(speaker, fill)
        if (muted) {
            canvas.drawLine(dp(11.5f), dp(6.5f), dp(16.5f), dp(11.5f), stroke)
            canvas.drawLine(dp(16.5f), dp(6.5f), dp(11.5f), dp(11.5f), stroke)
        } else {
            for (i in 1..3) {
                val on = (shownLevel - (i - 1)).coerceIn(0f, 1f)
                if (on <= 0f) continue
                val r = dp(2.6f * i + 0.4f)
                arc.set(dp(8.6f) - r, dp(9f) - r, dp(8.6f) + r, dp(9f) + r)
                withAlpha(stroke, on) { canvas.drawArc(arc, -48f, 96f, false, stroke) }
            }
        }
        canvas.restore()
    }
}

/**
 * Horizontal battery with the percentage inside: the number is dark over the filled part and white
 * over the empty part. Green while charging (with a small bolt), red when low, amber with the saver.
 */
class BatteryDrawable(density: Float, private val color: Int, typeface: Typeface?) :
    AnimatedLevelDrawable(density, color) {
    private var level = 0
    private var charging = false
    private var low = false
    private var saver = false
    private val body = RectF()
    private val inner = RectF()
    private val filled = RectF()
    private val bolt = Path()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, 700, false)
    }

    fun set(level: Int, charging: Boolean, saver: Boolean) {
        val changed = level != this.level || charging != this.charging || saver != this.saver
        this.level = level.coerceIn(0, 100)
        this.charging = charging
        this.saver = saver
        low = this.level <= LOW && !charging
        animateTo(this.level.toFloat())
        if (changed) invalidateSelf()
    }

    override fun getIntrinsicWidth() = dp(30f).roundToInt()
    override fun getIntrinsicHeight() = dp(14f).roundToInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val scale = min(b.width() / dp(30f), b.height() / dp(14f))
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top + (b.height() - dp(14f) * scale) / 2f)
        canvas.scale(scale, scale)

        // Body and the small terminal on the right
        stroke.strokeWidth = dp(1.2f)
        body.set(dp(0.6f), dp(0.6f), dp(27.4f), dp(13.4f))
        withAlpha(stroke, 0.85f) { canvas.drawRoundRect(body, dp(3.4f), dp(3.4f), stroke) }
        withAlpha(fill, 0.85f) {
            canvas.drawRoundRect(dp(28.2f), dp(4.6f), dp(29.8f), dp(9.4f), dp(0.8f), dp(0.8f), fill)
        }

        // Charge, animated
        inner.set(dp(2f), dp(2f), dp(26f), dp(12f))
        filled.set(inner.left, inner.top, inner.left + inner.width() * shownLevel / 100f, inner.bottom)
        val chargeColor = when {
            charging -> CHARGING
            low -> LOW_COLOR
            saver -> SAVER
            else -> color
        }
        val old = fill.color
        fill.color = chargeColor
        canvas.drawRoundRect(filled, dp(1.8f), dp(1.8f), fill)
        fill.color = old

        // Percentage (and a bolt while charging), two-tone across the fill edge
        text.textSize = dp(8.4f)
        val label = level.toString()
        val boltWidth = if (charging) dp(5f) else 0f
        val textWidth = text.measureText(label)
        val start = inner.centerX() - (boltWidth + textWidth) / 2f
        val baseline = inner.centerY() - (text.descent() + text.ascent()) / 2f
        if (charging) buildBolt(start, inner.centerY())
        for (overFill in listOf(true, false)) {
            canvas.save()
            if (overFill) canvas.clipRect(filled) else canvas.clipOutRect(filled)
            val ink = if (overFill) DARK_INK else color
            text.color = ink
            canvas.drawText(label, start + boltWidth + textWidth / 2f, baseline, text)
            if (charging) {
                fill.color = ink
                canvas.drawPath(bolt, fill)
                fill.color = old
            }
            canvas.restore()
        }
        canvas.restore()
    }

    private fun buildBolt(left: Float, centerY: Float) {
        bolt.reset()
        bolt.moveTo(left + dp(2.6f), centerY - dp(4f))
        bolt.lineTo(left + dp(0.4f), centerY + dp(0.6f))
        bolt.lineTo(left + dp(2f), centerY + dp(0.6f))
        bolt.lineTo(left + dp(1.4f), centerY + dp(4f))
        bolt.lineTo(left + dp(3.8f), centerY - dp(0.8f))
        bolt.lineTo(left + dp(2.2f), centerY - dp(0.8f))
        bolt.close()
    }

    private companion object {
        const val LOW = 20
        val CHARGING = Color.parseColor("#4ADE80")
        val LOW_COLOR = Color.parseColor("#F87171")
        val SAVER = Color.parseColor("#FBBF24")
        val DARK_INK = Color.parseColor("#0E1420")
    }
}
