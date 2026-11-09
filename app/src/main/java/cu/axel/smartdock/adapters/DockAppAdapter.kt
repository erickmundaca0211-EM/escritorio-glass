package cu.axel.smartdock.adapters

import androidx.recyclerview.widget.DiffUtil
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import cu.axel.smartdock.R
import cu.axel.smartdock.models.DockApp
import cu.axel.smartdock.utils.AppUtils
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.IconPackUtils
import cu.axel.smartdock.utils.Utils

class DockAppAdapter(
    private val context: Context, private var apps: ArrayList<DockApp>,
    private val listener: OnDockAppClickListener, private val iconPackUtils: IconPackUtils?
) : RecyclerView.Adapter<DockAppAdapter.ViewHolder>() {
    private var iconBackground = 0
    private val iconPadding: Int
    private val iconTheming: Boolean
    private val tintIndicators: Boolean
    private val accentIndicator: Int?

    interface OnDockAppClickListener {
        fun onDockAppClicked(app: DockApp, view: View)
        fun onDockAppLongClicked(app: DockApp, view: View)
    }

    private companion object {
        const val OPEN_INDICATOR = 0xB3FFFFFF.toInt() // white 70 %
        const val INDICATOR_MS = 200L
        const val PRESS_MS = 90L
        const val RELEASE_MS = 220L
        const val PRESSED_SCALE = 0.84f
    }

    /** What the list showed last time, to tell DiffUtil which icons came, went or changed. */
    private data class Shown(val packageName: String, val windows: Int, val active: Boolean)

    private var shown: List<Shown> = snapshot(apps)

    private fun snapshot(list: List<DockApp>) = list.map {
        Shown(it.packageName, it.tasks.count { task -> task.id != -1 }, isActive(it))
    }

    private fun isActive(app: DockApp) = app.tasks.isNotEmpty() && app.packageName == AppUtils.currentApp

    init {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        iconTheming = sharedPreferences.getString("icon_pack", "") != ""
        iconPadding =
            Utils.dpToPx(context, sharedPreferences.getString("icon_padding", "5")!!.toInt())
        tintIndicators = sharedPreferences.getBoolean("tint_indicators", false)
        accentIndicator = if (ColorUtils.isGlassTheme(sharedPreferences))
            ColorUtils.getAccentColor(sharedPreferences) else null
        when (sharedPreferences.getString("icon_shape", "circle")) {
            "circle" -> iconBackground = R.drawable.circle
            "round_rect" -> iconBackground = R.drawable.round_square
            "default" -> iconBackground = -1
        }
        // Escritorio Glass taskbar shows the apps' own icons, like Windows 11
        if (accentIndicator != null) iconBackground = -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, arg1: Int): ViewHolder {
        val itemLayoutView =
            // Inflate against the parent so the 44x42 dp button size and margins are kept
            LayoutInflater.from(parent.context).inflate(R.layout.app_task_entry, parent, false)
        return ViewHolder(itemLayoutView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val app = apps[position]
        val size = app.tasks.size
        val active = isActive(app)
        // Same app redrawn (it became active or not): the pill grows or shrinks; a new one starts there
        val sameApp = viewHolder.boundPackage == app.packageName
        viewHolder.boundPackage = app.packageName
        viewHolder.itemView.isActivated = active && accentIndicator != null
        if (size > 0) {
            if (tintIndicators)
                ColorUtils.applyColor(
                    viewHolder.runningIndicator,
                    ColorUtils.manipulateColor(ColorUtils.getDrawableDominantColor(app.icon), 2f)
                )
            else if (accentIndicator != null)
                // Active app: accent pill; other open apps: soft white pill (docs/DISENO.md)
                ColorUtils.applyColor(
                    viewHolder.runningIndicator,
                    if (active) accentIndicator else OPEN_INDICATOR
                )

            viewHolder.runningIndicator.alpha = if (app.tasks[0].id != -1) 1f else 0f
            viewHolder.setIndicatorWidth(Utils.dpToPx(context, if (active) 16 else 6), sameApp)

            if (size > 1) {
                viewHolder.taskCounter.text = size.toString()
                viewHolder.taskCounter.alpha = 1f
            } else
                viewHolder.taskCounter.alpha = 0f
        } else {
            viewHolder.runningIndicator.alpha = 0f
            viewHolder.taskCounter.alpha = 0f
        }
        if (iconPackUtils != null)
            viewHolder.iconIv.setImageDrawable(iconPackUtils.getAppThemedIcon(app.packageName))
        else
            viewHolder.iconIv.setImageDrawable(app.icon)
        if (iconBackground != -1) {
            viewHolder.iconIv.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            viewHolder.iconIv.setBackgroundResource(iconBackground)
            ColorUtils.applyColor(
                viewHolder.iconIv,
                ColorUtils.getDrawableDominantColor(viewHolder.iconIv.drawable)
            )
        }
        viewHolder.bind(app, listener)
    }

    override fun getItemCount(): Int {
        return apps.size
    }

    /** Applies only the differences, so opened and closed apps animate (TaskbarItemAnimator). */
    fun updateApps(newApps: ArrayList<DockApp>) {
        val old = shown
        val new = snapshot(newApps)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].packageName == new[n].packageName
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
        })
        apps = newApps
        shown = new
        diff.dispatchUpdatesTo(this)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var iconIv: ImageView = itemView.findViewById(R.id.icon_iv)
        var taskCounter: TextView = itemView.findViewById(R.id.task_count_badge)
        var runningIndicator: View = itemView.findViewById(R.id.running_indicator)
        var boundPackage: String? = null
        private var indicatorAnimator: ValueAnimator? = null

        /** The open/active pill: it stretches to the new width when the app becomes active or not. */
        fun setIndicatorWidth(width: Int, animate: Boolean) {
            indicatorAnimator?.cancel()
            val params = runningIndicator.layoutParams
            if (!animate || params.width == width || params.width <= 0) {
                params.width = width
                runningIndicator.layoutParams = params
                return
            }
            indicatorAnimator = ValueAnimator.ofInt(params.width, width).apply {
                duration = INDICATOR_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    params.width = it.animatedValue as Int
                    runningIndicator.layoutParams = params
                }
                start()
            }
        }

        fun bind(app: DockApp, listener: OnDockAppClickListener) {
            itemView.setOnClickListener { view -> listener.onDockAppClicked(app, view) }
            itemView.setOnLongClickListener { view ->
                listener.onDockAppLongClicked(app, view)
                true
            }
            itemView.setOnTouchListener { view, event ->
                if (event.buttonState == MotionEvent.BUTTON_SECONDARY) {
                    listener.onDockAppLongClicked(app, view)
                    return@setOnTouchListener true
                }
                // Like Windows 11: the icon squeezes a little while pressed and springs back
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> iconIv.animate().scaleX(PRESSED_SCALE)
                        .scaleY(PRESSED_SCALE).setDuration(PRESS_MS).start()

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> iconIv.animate().scaleX(1f)
                        .scaleY(1f).setDuration(RELEASE_MS)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }
                false
            }
        }
    }
}